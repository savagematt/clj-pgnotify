(ns clj-pgnotify.test-util
  (:require [clojure.java
             [io :as io]])
  (:import [java.util Properties]))

(def props-path "donotcheckin/postgres.properties")

(def props (delay
             (if-let [url (io/resource props-path)]
               (doto (Properties.)
                 (.load (io/input-stream url)))
               (println "Test not running- create"
                        (str "test/" props-path)
                        "to run test. \n"
                        "NB: TESTS WILL KILL ALL CONNECTIONS TO THE POSTGRES INSTANCE ON YOUR MACHINE \n"
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
