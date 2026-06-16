(ns mcp-toolkit.impl.logging
  #?(:clj (:import [java.time Instant])
     :default nil))

(def ^:dynamic *request-id*
  "Correlation ID for the current request. Bound at the transport level,
   propagated through handler dispatch. Included in all log events."
  nil)

(def ^:dynamic *log-fn*
  "Overrideable logging function. Called as (fn [level event data]).
   Default: structured JSON println to stderr.
   level  - \"info\", \"warn\", \"error\", etc.
   event  - short kebab-case string identifying the event type
   data   - map of key-value pairs to include in the log entry"
  nil)

(defn- timestamp
  []
  #?(:clj (str (Instant/now))
     :bb (str (java.time.Instant/now))
     :default (str (js/Date.))))

(defn- json-encode [x]
  #?(:clj
     (if-some [cheshire (try (requiring-resolve 'cheshire.core/generate-string)
                             (catch Exception _ nil))]
       (cheshire x)
       (pr-str x))
     :bb
     (pr-str x)
     :default
     (pr-str x)))

(defn log-event
  "Log a structured event. Merges *request-id* into data automatically if bound."
  [level event data]
  (let [f (fn [level event data]
            (let [line (json-encode
                        (merge {:timestamp (timestamp)
                                :level level
                                :event event}
                               (when *request-id*
                                 {:request-id *request-id*})
                               data))]
              (binding [*out* *err*]
                (println line))))]
    ((or *log-fn* f) level event data)))
