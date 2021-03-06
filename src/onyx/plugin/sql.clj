(ns onyx.plugin.sql
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.core.async :refer [chan >! >!! <!! close! go timeout alts!! go-loop]]
            [taoensso.timbre :refer [fatal]]
            [onyx.peer.pipeline-extensions :as p-ext]
            [onyx.types :as t]
            [onyx.static.default-vals :refer [arg-or-default]]
            [onyx.static.uuid :refer [random-uuid]]
            [onyx.peer.function :as function]
            [onyx.extensions :as extensions]
            [taoensso.timbre :refer [info error debug fatal]]
            [honeysql.core :as sql]
            [java-jdbc.sql :as sql-dsl])
  (:import [com.mchange.v2.c3p0 ComboPooledDataSource]))

(defn create-pool [spec]
  {:datasource
   (doto (ComboPooledDataSource.)
     (.setDriverClass (:classname spec))
     (.setJdbcUrl (str "jdbc:" (:subprotocol spec) ":" (:subname spec)))
     (.setUser (:user spec))
     (.setPassword (:password spec))
     (.setMaxIdleTimeExcessConnections (* 30 60))
     (.setMaxIdleTime (* 3 60 60)))})

(defn task->pool [task-map]
  (let [db-spec {:classname (:sql/classname task-map)
                 :subprotocol (:sql/subprotocol task-map)
                 :subname (:sql/subname task-map)
                 :user (:sql/user task-map)
                 :password (:sql/password task-map)}]
    (create-pool db-spec)))

(defn partition-table [{:keys [onyx.core/task-map sql/pool] :as event}]
  (let [table (name (:sql/table task-map))
        id-col (name (:sql/id task-map))
        n-min (or (:sql/lower-bound task-map)
                  (:min (first (jdbc/query pool (vector (format "select min(%s) as min from %s" id-col table))))))
        n-max (or (:sql/upper-bound task-map)
                  (:max (first (jdbc/query pool (vector (format "select max(%s) as max from %s" id-col table))))))
        ranges (partition-all 2 1 (range n-min n-max (:sql/rows-per-segment task-map)))]
    (map (fn [[l h]]
           {:low l
            :high (dec (or h (inc n-max)))
            :table (:sql/table task-map)
            :id (:sql/id task-map)})
         ranges)))

(defn update-partition [content acked]
  (dissoc content acked))

(defn start-commit-loop! [log task-id content checkpoint-ch checkpoint-loop-ms]
  (go-loop [updated-content content]
           (let [timeout-ch (timeout checkpoint-loop-ms)
                 [acked ch] (alts!! [timeout-ch checkpoint-ch] :priority true)] 
             (cond (= ch timeout-ch)
                   (do 
                     (extensions/force-write-chunk log :chunk updated-content task-id)
                     (recur updated-content))
                   (and (= ch checkpoint-ch)
                        (not (nil? acked)))
                   (recur (update-partition updated-content acked))))))

(defn inject-partition-keys
  [{:keys [onyx.core/pipeline onyx.core/task-map onyx.core/log onyx.core/task-id] :as event} 
   lifecycle]
  (let [ch (:read-ch pipeline)
        checkpoint-ch (:checkpoint-ch pipeline)
        checkpoint-ms (:checkpoint-ms pipeline)
        pending-messages (:pending-messages pipeline)
        pool (task->pool task-map)
        partitions (partition-table (assoc event :sql/pool pool))
        content (:content pipeline)
        chunk (into {} 
                    (map (fn [p] [p :incomplete]) 
                         partitions))
        ;; Attempt to write. It will fail if it's already been written. Read it back
        ;; in either case.
        _ (extensions/write-chunk log :chunk chunk task-id)
        content (extensions/read-chunk log :chunk task-id)
        commit-go-loop (start-commit-loop! log task-id content checkpoint-ch checkpoint-ms)]
    (go
     (try
         (doseq [part (keys content)]
           (>! ch part))
         (>! ch :done)
       (catch Exception e
         (fatal e))))
    {:sql/pool pool
     :sql/read-ch ch
     :sql/pending-messages pending-messages}))

(defn close-partition-keys
  [{:keys [sql/pool] :as event} lifecycle]
  (close! (:checkpoint-ch (:onyx.core/pipeline event)))
  (.close (:datasource pool))
  {})

(defrecord SqlPartitionKeys [max-pending batch-size batch-timeout log task-id 
                             pending-messages drained? read-ch checkpoint-ch checkpoint-ms]
  p-ext/Pipeline
  (write-batch 
    [this event]
    (function/write-batch event))

  (read-batch [_ event]
    (let [pending (count (keys @pending-messages))
          max-segments (min (- max-pending pending) batch-size)
          timeout-ch (timeout batch-timeout)
          batch (->> (range max-segments)
                     (map (fn [_]
                            (let [result (first (alts!! [read-ch timeout-ch] :priority true))]
                              (if (= result :done)
                                (t/input (random-uuid) :done)
                                (t/input (random-uuid) result)))))
                     (filter :message))]
      (doseq [m batch]
        (swap! pending-messages assoc (:id m) m))
      (when (and (= 1 (count @pending-messages))
                 (= (count batch) 1)
                 (zero? (count (.buf read-ch)))
                 (= (:message (first batch)) :done))
        (reset! drained? true))
      {:onyx.core/batch batch}))

  p-ext/PipelineInput

  (ack-segment [_ _ segment-id]
    (let [part (get @pending-messages segment-id)]
      (>!! checkpoint-ch (:message part))
      (swap! pending-messages dissoc segment-id)))

  (retry-segment 
    [_ _ segment-id]
    (let [snapshot @pending-messages
          message (get snapshot segment-id)]
      (swap! pending-messages dissoc segment-id)
      (if (:partition message)
        (>!! read-ch message)
        (>!! read-ch :done))))

  (pending?
    [_ _ segment-id]
    (get @pending-messages segment-id))

  (drained? 
    [_ _]
    @drained?))

(defn partition-keys [pipeline-data]
  (let [catalog-entry (:onyx.core/task-map pipeline-data)
        max-pending (arg-or-default :onyx/max-pending catalog-entry)
        batch-size (:onyx/batch-size catalog-entry)
        batch-timeout (arg-or-default :onyx/batch-timeout catalog-entry)
        read-ch (chan (or (:sql/read-buffer catalog-entry) 1000))
        checkpoint-ch (chan (or (:sql/checkpoint-buffer catalog-entry) 1000))
        checkpoint-ms (or (:sql/checkpoint-ms catalog-entry) 500)
        pending-messages (atom {})
        drained? (atom false)
        log (:onyx.core/log pipeline-data)
        task-id (:onyx.core/task-id pipeline-data)]
    (->SqlPartitionKeys max-pending batch-size batch-timeout log task-id
                        pending-messages drained? read-ch checkpoint-ch checkpoint-ms)))

(defn inject-read-rows
  [{:keys [onyx.core/task-map] :as event} lifecycle]
  (let [pool (task->pool task-map)]
    {:sql/pool pool
     :onyx.core/params [pool]}))

(defn close-read-rows
  [{:keys [sql/pool] :as event} lifecycle]
  (.close (:datasource pool))
  {})

(defn read-rows [pool {:keys [table id low high] :as segment}]
  (let [sql-map {:select [:*]
                 :from [table]
                 :where [:and
                         [:>= id low]
                         [:<= id high]]}]
    (jdbc/query pool (sql/format sql-map))))

(defn inject-write-rows
  [{:keys [onyx.core/pipeline] :as event} lifecycle]
  {:sql/pool (:pool pipeline)})

(defn close-write-rows
  [{:keys [sql/pool] :as event} lifecycle]
  (.close (:datasource pool))
  {})

(defn inject-upsert-rows
  [{:keys [onyx.core/task-map] :as event} lifecycle]
  {:sql/pool (task->pool task-map)})

(defn close-update-rows
  [{:keys [sql/pool] :as event} lifecycle]
  (.close (:datasource pool))
  {})

(defrecord SqlWriteRows [pool table]
  p-ext/Pipeline
  (read-batch 
    [_ event]
    (function/read-batch event))

  (write-batch 
    [_ {:keys [onyx.core/results]}]
    (doseq [msg (mapcat :leaves (:tree results))]
      (jdbc/with-db-transaction
        [conn pool]
        (doseq [row (:rows (:message msg))]
          (jdbc/insert! conn table row))))
    {:onyx.core/written? true})

  (seal-resource 
    [_ {:keys [onyx.core/results]}]
    {}))

(defrecord SqlUpsertRows [pool table]
  p-ext/Pipeline
  (read-batch 
    [_ event]
    (function/read-batch event))

  (write-batch
    [_ {:keys [onyx.core/results onyx.core/task-map sql/pool]}]
    (doseq [msg (mapcat :leaves (:tree results))]
      (jdbc/with-db-transaction
        [conn pool]
        (doseq [row (:rows (:message msg))]
          (jdbc/update! conn (:sql/table task-map) row (sql-dsl/where (:where (:message msg)))))))
    {:onyx.core/written? true})

  (seal-resource 
    [_ event]
    {}))

(defn write-rows [pipeline-data]
  (let [task-map (:onyx.core/task-map pipeline-data)
        table (:sql/table task-map)
        pool (task->pool task-map)]
    (->SqlWriteRows pool table)))

(defn upsert-rows [pipeline-data]
  (let [task-map (:onyx.core/task-map pipeline-data)
        table (:sql/table task-map)
        pool (task->pool task-map)]
    (->SqlUpsertRows pool table)))

(def partition-keys-calls
  {:lifecycle/before-task-start inject-partition-keys
   :lifecycle/after-task-stop close-partition-keys})

(def read-rows-calls
  {:lifecycle/before-task-start inject-read-rows
   :lifecycle/after-task-stop close-read-rows})

(def write-rows-calls
  {:lifecycle/before-task-start inject-write-rows
   :lifecycle/after-task-stop close-write-rows})

(def upsert-rows-calls
  {:lifecycle/before-task-start inject-upsert-rows
   :lifecycle/after-task-stop close-update-rows})
