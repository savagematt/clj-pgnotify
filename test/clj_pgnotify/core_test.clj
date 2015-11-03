(ns clj-pgnotify.core-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojure.core.async :refer [<!! go alts!! timeout close!]]
            [clj-pgnotify.core :refer :all]
            [clj-pgnotify.test-util :refer [db props]])
  (:import [java.util UUID]))

(when @props
  (fact "pub sub works"
    (with-open [connection-passed-to-subscription (sql/get-connection @db)]
      (let [error-atom   (atom nil)
            channel-name (str "chan" (string/replace (str (UUID/randomUUID)) #"[^A-Za-z0-9_]" ""))
            payload      (str (UUID/randomUUID))

            sub          (listen! (pg-listener [channel-name]
                                            :ex-handler (fn [e] (reset! error-atom e)))
                                 connection-passed-to-subscription)]

        (sql/with-db-transaction [cnxn @db]
          (pg-notify! cnxn channel-name payload))

        (fact "We get the first published message"
          (first (alts!! [sub (timeout 1000)]))
          => [{:channel channel-name
               :payload payload}])

        (fact "no errors are reported"
          @error-atom => nil))))

  (fact "database dies"
    (with-open [connection-passed-to-subscription (sql/get-connection @db)]
      (let [error-atom             (atom nil)
            channel-name           (str "chan" (string/replace (str (UUID/randomUUID)) #"[^A-Za-z0-9_]" ""))
            payload                (str (UUID/randomUUID))

            heartbeat-frequency-ms 10
            heartbeat              (default-heartbeat :dummy-query-timeout-seconds 1
                                                      :poll-server-socket-ms heartbeat-frequency-ms)
            sub                    (listen! (pg-listener [channel-name]
                                                         :ex-handler (fn [e] (reset! error-atom e))
                                                         :poll (default-poller :heartbeat heartbeat))
                                            connection-passed-to-subscription)
            ]

        (sql/with-db-transaction [cnxn @db]
          (pg-notify! cnxn channel-name payload))

        ; Originally published message
        (fact "We get the first published message"
          (first (alts!! [sub (timeout 1000)]))
          => [{:channel channel-name
               :payload payload}])

        (fact "the test killed all running connections to the server"
          (sql/with-db-transaction [cnxn @db]
            (sql/query cnxn ["SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE pid <> pg_backend_pid();"])))

        (Thread/sleep heartbeat-frequency-ms)

        (fact "channel should be closed"
          (first (alts!! [sub] :default "nothing in channel"))
          => nil)

        (fact "the error should be reported"
          @error-atom
          => truthy)))
    )

  (fact "closing db connection"
    (with-open [connection-passed-to-subscription (sql/get-connection @db)]
      (let [error-atom             (atom nil)
            channel-name           (str "chan" (string/replace (str (UUID/randomUUID)) #"[^A-Za-z0-9_]" ""))
            payload                (str (UUID/randomUUID))

            heartbeat-frequency-ms 10
            heartbeat              (default-heartbeat :dummy-query-timeout-seconds 1
                                                      :poll-server-socket-ms heartbeat-frequency-ms)
            sub                    (listen! (pg-listener [channel-name]
                                                         :ex-handler (fn [e] (reset! error-atom e))
                                                         :poll (default-poller :heartbeat heartbeat))
                                            connection-passed-to-subscription)
            ]

        (sql/with-db-transaction [cnxn @db]
          (pg-notify! cnxn channel-name payload))

        ; Originally published message
        (fact "We get the first published message"
          (first (alts!! [sub (timeout 1000)]))
          => [{:channel channel-name
               :payload payload}])

        (fact "the test closed the connection"
          (.close connection-passed-to-subscription))
        (Thread/sleep heartbeat-frequency-ms)

        (fact "channel should be closed"
          (first (alts!! [sub] :default "nothing in channel"))
          => nil)

        (fact "the error should be reported"
          @error-atom
          => truthy)))
    ))
