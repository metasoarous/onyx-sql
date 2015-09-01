(defproject org.onyxplatform/onyx-sql "0.7.3-SNAPSHOT"
  :description "Onyx plugin for JDBC-backed SQL databases"
  :url "https://github.com/MichaelDrogalis/onyx-sql"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/java.jdbc "0.3.3"]
                 ^{:voom {:repo "git@github.com:onyx-platform/onyx.git" :branch "master"}}
                 [org.onyxplatform/onyx "0.7.3-20150901_142736-gd0de107"]
                 [java-jdbc/dsl "0.1.3"]
                 [com.mchange/c3p0 "0.9.2.1"]
                 [honeysql "0.5.1"]]
  :profiles {:dev {:dependencies [[midje "1.7.0"]
                                  [environ "1.0.0"]
                                  [mysql/mysql-connector-java "5.1.25"]]
                   :plugins [[lein-midje "3.1.3"]]}
             :circle-ci {:jvm-opts ["-Xmx4g"]}})
