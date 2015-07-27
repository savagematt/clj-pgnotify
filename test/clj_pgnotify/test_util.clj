(ns clj-pgnotify.test-util
  (:require [clojure.java
             [io :as io]
             [shell :as shell]])
  (:import [java.util Properties]))

(def props-path "donotcheckin/postgres.properties")

(def props (delay
             (if-let [url (io/resource props-path)]
               (doto (Properties.)
                 (.load (io/input-stream url)))
               (println "Test not running- create"
                        (str "test/" props-path)
                        "to run test. \n"
                        "NB: TESTS WILL STOP AND START THE POSTGRES INSTANCE ON YOUR MACHINE \n"
                        "Example files are available in test/example_properties. \n"
                        "The donotcheckin directory should be in .gitignore. Double check it really is before commiting."))))

(def db (delay
          {
           :classname   "org.postgresql.Driver",
           :subprotocol "postgresql",
           :user        (.getProperty @props "user"),
           :password    (.getProperty @props "password"),
           :subname     (.getProperty @props "subname")
           }))

(defmacro immediate-print
  "Get immediate feedback in System/out when running inside a go loop"
  [& forms]
  `(binding [clojure.core/*out* (clojure.java.io/writer (System/out))]
     ~@forms))

(defn sh
  "Wrap clojure.java.shell/sh with some logging and immediate printing to System/out
  when in a go loop"
  [command-string]
  (immediate-print
    (println)
    (println "Executing " command-string)
    (let [result (apply shell/sh (clojure.string/split command-string #" "))]
      (when (not= 0 (:exit result))
        (clojure.pprint/pprint result)))))

(defn start-postgres []
  (sh (.getProperty @props "start-postgres")))

(defn stop-postgres []
  (sh (.getProperty @props "stop-postgres")))
