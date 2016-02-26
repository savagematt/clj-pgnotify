(ns clj-pgnotify.core
  (:require [clojure.java.jdbc :as sql]
            [clojure.core.async :refer [chan go go-loop >!! >! <! close! timeout alts! onto-chan]])
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
     (>! ~errors [:error e#])
     (recur)))

(defn run-every-ms [ms func & [value-when-not-run]]
  (let [a (atom (System/currentTimeMillis))]
    (fn [& args]
      (let [now (System/currentTimeMillis)]
        (if (<= ms (- now @a) )
          (do (reset! a now)
              (apply func args))
          value-when-not-run)))))

(defn default-heartbeat [& {:keys [poll-server-socket-ms
                                   dummy-query-sql
                                   dummy-query-timeout-seconds]
                            :or   {poll-server-socket-ms       3000
                                   dummy-query-sql             "SELECT 1"
                                   dummy-query-timeout-seconds 5
                                   }}]
  (run-every-ms poll-server-socket-ms
    (fn [cnxn]
      (try-dummy-query cnxn dummy-query-sql dummy-query-timeout-seconds))))

(defn default-poller
  "Returns a function that:

  - Takes a PGConnection
  - Calls :heartbeat with the connection to check the server is still listening
  - Gets notifications from the connection
  - Returns a vector of {:channel \"channel_name\" :payload \"some payload\"}

  Opts:

  :heartbeat
  An arity 1 function which:
  - takes a sql connection
  - runs some kind of no-op SQL statement, eg 'SELECT 1'
  - throws an exception if the statement fails
  - return value is ignored

  See default-heartbeat for an example
  "
  [& {:keys [poll-notifications-ms
             heartbeat]
      :or   {poll-notifications-ms 10
             heartbeat             (default-heartbeat)}}]

  (fn [^PGConnection cnxn]
    (heartbeat cnxn)

    (->> (get-notifications cnxn)
         (mapv (fn [^PGNotification n]
                 {:channel (.getName n)
                  :payload (.getParameter n)})))))

(defprotocol Listener
  (listen! [this cnxn]))

(defn pg-listener
  "Starts listening to channel-names using cnxn.

  Returns a Listener, which is a protocol with a single listen! function, which takes a PGConnection

  listen! returns an output channel, which will close when the connection is closed, or when the heartbeat
  with the server fails.

  Opts:

  :ex-handler
  An arity 1 function which will be passed the exception should there be errors getting notifications
  or heartbeating with the server

  :poll
  An arity 1 function which
  - takes a sql connection
  - checks for new notifications, by calling (.getNotifications cnxn) and possibly mapping to so other structure
  - returns the (possibly mapped) notifications

  It is assumed that if no notifications are received, the :poll function will end with (<!! (timeout some-timeout))
  in order to implement throttling

  See default-poller for an example

  When either :poll or :heartbeat return an exception, the output channel will be closed.
  "
  [channel-names
   & {:keys [ex-handler
             poll]

      :or   {ex-handler clojure.stacktrace/print-cause-trace
             poll       (default-poller)}}]

   (reify Listener
    (listen! [_this cnxn]
      (let [notifications (chan 0)
            output        (chan 0)]

        (pg-listen cnxn channel-names)

        ; Polling loop for notifications
        (go-loop []
          (report-errors-or-recur notifications
            (let [ns (poll cnxn)]
              (when-not (empty? ns)
                (>! notifications [:cont ns])))))

        ; Control loop merging notifications into output and cleaning up on error
        (go-loop []
          (let [[message-type value] (<! notifications)]
            (case  message-type
              :cont
              (do (>! output value)
                  (recur))

              :error
              (do (close! notifications)
                  (close! output)
                  (ex-handler value)))))

        output))))

(defn pg-notify! [db channel-name payload]
  (try
    (sql/execute! db [(str "NOTIFY " channel-name ", '" payload "'")])
    (catch SQLException e
      (if (.getNextException e)
        (throw (.getNextException e))
        (throw e)))))
