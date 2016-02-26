(defproject savagematt/clj-pgnotify "0.1.1-SNAPSHOT"
  :description "Wraps Postgres pg_notify in core.async channels"
  :url "http://github.com/savagematt/slj-pgnotify"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.postgresql/postgresql "9.4-1201-jdbc41"]
                 [org.clojure/java.jdbc "0.3.6"]
                 [org.clojure/core.async "0.2.374"]
                 ]
  :profiles {:dev      {:dependencies [[midje "1.6.3"]]

                        :plugins [[lein-set-version "0.4.1"]
                                  [lein-midje "3.1.0"]]}})
