(ns clj-pgnotify.core-test
  (:require [midje.sweet :refer :all]
            [clojure.java.jdbc :as sql]
            [clojure.string :as string]
            [clojure.core.async :refer [<!! go alts!! timeout close!]]
            [clj-pgnotify.core :refer :all]
            [clj-pgnotify.test-util :refer [stop-postgres start-postgres db props]])
  (:import [java.util UUID]
           [java.sql SQLException Connection]))

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
    (try
      (with-open [connection-passed-to-subscription (sql/get-connection @db)]
        (let [error-atom   (atom nil)
              channel-name (str "chan" (string/replace (str (UUID/randomUUID)) #"[^A-Za-z0-9_]" ""))
              payload      (str (UUID/randomUUID))

              sub          (listen! (pg-listener [channel-name]
                                              :ex-handler (fn [e] (reset! error-atom e))
                                              :poll (default-poller :heartbeat (default-heartbeat :poll-server-socket-ms 10)))
                                   connection-passed-to-subscription)
              ]

          (sql/with-db-transaction [cnxn @db]
            (pg-notify! cnxn channel-name payload))

          ; Originally published message
          (fact "We get the first published message"
            (first (alts!! [sub (timeout 1000)]))
            => [{:channel channel-name
                 :payload payload}])

          (stop-postgres)

          (fact "database connection should be screwed for test to be valid"
            (sql/with-db-transaction [cnxn @db]
              (pg-notify! cnxn channel-name payload))
            => (throws SQLException))

          (fact "channel should be closed"
            (first (alts!! [sub] :default "nothing in channel"))
            => nil)

          (fact "the error should be reported"
            @error-atom
            => truthy)))
      (finally
        (start-postgres))))

  (fact "closing db connection"
    (with-open [^Connection connection-passed-to-subscription (sql/get-connection @db)]
      (let [error-atom            (atom nil)

            channel-name          (str "chan" (string/replace (str (UUID/randomUUID)) #"[^A-Za-z0-9_]" ""))
            payload               (str (UUID/randomUUID))

            poll-notifications-ms 10
            sub                   (listen! (pg-listener [channel-name]
                                                     :ex-handler (fn [e] (reset! error-atom e))
                                                     :poll (default-poller :poll-notifications-ms poll-notifications-ms))
                                          connection-passed-to-subscription)]

        (sql/with-db-transaction [cnxn @db]
          (pg-notify! cnxn channel-name payload))

        (fact "We get the first published message"
          (first (alts!! [sub (timeout 1000)]))
          => [{:channel channel-name
               :payload payload}])

        (.close connection-passed-to-subscription)

        (Thread/sleep (* 2 poll-notifications-ms))

        (fact "channel should be closed"
          (first (alts!! [sub] :default "nothing in channel"))
          => nil)

        (fact "the error should be reported"
          @error-atom
          => truthy)
        ))))
