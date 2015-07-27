(ns clj-pgnotify.core
  (:require [clojure.java.jdbc :as sql]
            [clojure.core.async :refer [chan go go-loop >!! <!! >! <! close! timeout alts!]])
  (:import [java.sql Statement SQLException Connection PreparedStatement]
           [org.postgresql PGConnection PGNotification]))

(defn get-notifications [^PGConnection cnxn]
  (try
    (.getNotifications cnxn)
    (catch Exception e
      (throw (ex-info (str "There was a problem getting notifications: " (.getMessage e))
                      {:error-type :get-notifications-failed}
                      e)))))

(defn try-dummy-query [cnxn sql timeout-seconds]
  (try
    (with-open [^PreparedStatement stmt (sql/prepare-statement cnxn sql :timeout timeout-seconds)]
      (.executeQuery stmt))

    (catch Exception e
      (throw (ex-info (str "There was a problem with the dummy query statement: " (.getMessage e))
                      {:error-type :dummy-query-failed}
                      e)))))

(defn pg-listen [^Connection cnxn channel-names]
  (try
    (with-open [stmt (->> channel-names
                          (reduce (fn [^Statement stmt channel-name]
                                    (doto stmt
                                      (.addBatch (str "LISTEN " channel-name))))
                                  (.createStatement cnxn)
                                  ))]
      (.executeBatch stmt))
    (catch Throwable e
      (throw (ex-info (str "There was a problem listening to channels " channel-names ": " (.getMessage e))
                      {:error-type :listen-failed}
                      e)))))

(defmacro exception-or-nil [& body]
  `(try
     ~@body
     nil
     (catch Exception e# e#)))

(defmacro report-errors-or-recur [errors & body]
  `(if-let [e# (exception-or-nil ~@body)]
     (go (when (>! ~errors e#)))
     (recur)))

(defn default-heartbeat [& {:keys [poll-server-socket-ms
                                   dummy-query-sql
                                   dummy-query-timeout-seconds]
                            :or   {poll-server-socket-ms       3000
                                   dummy-query-sql             "SELECT 1"
                                   dummy-query-timeout-seconds 5
                                   }}]
  (fn [cnxn]
    (try-dummy-query cnxn dummy-query-sql dummy-query-timeout-seconds)
    (<!! (timeout poll-server-socket-ms))))

(defn default-poller [& {:keys [poll-notifications-ms]
                         :or   {poll-notifications-ms 10}}]
  (fn [^PGConnection cnxn]
    (if-let [ns (get-notifications cnxn)]
      (mapv (fn [^PGNotification n]
              {:channel (.getName n)
               :payload (.getParameter n)}) ns)
      (<!! (timeout poll-notifications-ms)))))

(defprotocol Poller
  (start! [this cnxn]))

(defn pg-subscriber
  "Starts listening to channel-names using cnxn.

  Returns a Poller, which can be start!ed with a PGConnection.

  start! returns an output channel, which will close when the connection is closed, or when the heartbeat
  with the server fails.

  Opts:

  :ex-handler
  An arity 1 function which will be passed the exception should there be errors getting notifications
  or heartbeating with the server

  :heartbeat
  An arity 1 function which:
  - takes a sql connection
  - runs some kind of no-op SQL statement, eg 'SELECT 1'
  - throws an exception if the statement fails
  - return value is ignored

  It is assumed that this function will end with (<!! (timeout some-timeout)) in order to implement
  throttling

  See default-heartbeat for an example

  :poll
  An arity 1 function which
  - takes a sql connection
  - checks for new notifications, by calling (.getNotifications cnxn) and possibly mapping to so other structure
  - returns the (possibly mapped) notifications

  It is assumed that if no notifications are received, this function will end with (<!! (timeout some-timeout))
  in order to implement throttling

  See default-poller for an example

  When either :poll or :heartbeat return an exception, the output channel will be closed.
  "
  [channel-names
   & {:keys [ex-handler
             heartbeat
             poll]

      :or   {ex-handler clojure.stacktrace/print-cause-trace
             heartbeat  (default-heartbeat)
             poll       (default-poller)}}]
  (reify Poller
    (start! [this cnxn]
      (let [notifications (chan 1)
            errors        (chan 1)
            output        (chan 0)]

        (pg-listen cnxn channel-names)

        ; Heartbeat loop checking the server's end of the socket is still open
        (go-loop []
          (report-errors-or-recur errors
            (heartbeat cnxn)))

        ; Polling loop for notifications
        (go-loop []
          (report-errors-or-recur errors
            (when-let [ns (poll cnxn)]
              (>! notifications ns))))

        ; Control loop merging notifications into output and cleaning up on error
        (go-loop []
          (let [[value _] (alts! [notifications errors] :priority true)]
            (if (instance? Throwable value)
              (do (close! notifications)
                  (close! errors)
                  (close! output)
                  (ex-handler value))
              (do (>! output value)
                  (recur)))))

        output))))

(defn pg-pub! [db channel-name payload]
  (try
    (sql/execute! db [(str "NOTIFY " channel-name ", '" payload "'")])
    (catch SQLException e
      (if (.getNextException e)
        (throw (.getNextException e))
        (throw e)))))
