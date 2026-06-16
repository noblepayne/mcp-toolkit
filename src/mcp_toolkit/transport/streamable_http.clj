(ns mcp-toolkit.transport.streamable-http
  "Streamable HTTP transport for MCP servers.

   Implements the MCP 2025-11-25 Streamable HTTP transport spec:
   https://modelcontextprotocol.io/specification/2025-11-25/basic/transports

   Features:
   - POST /mcp — client→server messages (JSON-RPC requests, notifications, responses)
   - GET /mcp  — server→client SSE stream (notifications, requests)
   - DELETE /mcp — session termination
   - Session management via MCP-Session-Id header
   - Origin validation (configurable, MUST for security)
   - Protocol version validation
   - Terminated session detection (tombstone)
   - Health check at /health

   Usage:
     (def dispatch-fn (fn [message session-id] ...))
     (def handler (create-handler dispatch-fn {:session-store (create-session-store)}))
     (http/run-server handler {:port 3000})"
  (:require [clojure.string :as str]
            [org.httpkit.server :as http]
            [cheshire.core :as json])
  (:import [java.time Instant]))

;; ─── Logging ────────────────────────────────────────────────────────────────

(def ^:dynamic *request-id*
  "Correlation ID for the current request. Bound by the handler wrapper."
  nil)

(def ^:dynamic *log-fn*
  "Overrideable logging function. Called as (fn [level event data]).
   Default: structured JSON println to stderr."
  nil)

(defn- log-event
  "Log a structured event. Thread-safe, no dependencies beyond json."
  [level event data]
  (let [default-fn (fn [level event data]
                     (binding [*out* *err*]
                       (println (json/generate-string
                                 (merge {:timestamp (str (Instant/now))
                                         :level level
                                         :event event}
                                        data)))))]
    ((or *log-fn* default-fn) level event data)))

(defn- with-request-logging
  "Middleware: log request lifecycle (received, completed, error)."
  [handler-fn request]
  (let [req-id (str (java.util.UUID/randomUUID))
        http-method (:request-method request)
        uri (:uri request)
        start-ms (System/currentTimeMillis)]
    (binding [*request-id* req-id]
      (log-event "info" "request-received"
                 {:request-id req-id :http-method http-method :uri uri})
      (try
        (let [response (handler-fn request)]
          (log-event "info" "request-completed"
                     {:request-id req-id :http-method http-method :uri uri
                      :status (:status response)
                      :duration-ms (- (System/currentTimeMillis) start-ms)})
          response)
        (catch Throwable t
          (log-event "error" "request-error"
                     {:request-id req-id :http-method http-method :uri uri
                      :error (.getMessage t)
                      :error-class (.getName (class t))
                      :duration-ms (- (System/currentTimeMillis) start-ms)})
          (throw t))))))

;; ─── Protocol Version ───────────────────────────────────────────────────────

(def supported-protocol-versions
  "Set of supported MCP protocol version strings."
  #{"2024-11-05" "2025-06-18" "2025-11-25"})

;; ─── HTTP Helpers ───────────────────────────────────────────────────────────

(defn- find-header
  "Find an HTTP header by name (case-insensitive).
   Handles both keyword and string keys, with any case."
  [request header-name]
  (let [headers (:headers request)
        low-name (str/lower-case header-name)]
    (or (get headers low-name)
        (get headers (str header-name))
        (get headers (keyword low-name))
        (get headers (keyword header-name))
        (some (fn [[k v]]
                (when (= low-name (str/lower-case (name k)))
                  v))
              headers))))

(defn- validate-protocol-version
  "Validate the MCP-Protocol-Version header.
   Returns nil if valid (or not provided — optional per spec),
   or an error response map if invalid."
  [request]
  (when-let [version (find-header request "MCP-Protocol-Version")]
    (when-not (contains? supported-protocol-versions version)
      {:jsonrpc "2.0"
       :error {:code -32600
               :message (str "Unsupported protocol version: " version)}})))

;; ─── Session Store ──────────────────────────────────────────────────────────

(defn create-session-store
  "Create an empty session store.
   Internal structure:
     {:sessions   {sid {:created-at ts :last-active ts :sse-channels {ch-id {:counter (atom N)}}}}
      :terminated {sid {:terminated-at ts}}
      :pending-sse-messages {sid [{:jsonrpc ...} ...]}}"
  []
  (atom {:sessions {} :terminated {} :pending-sse-messages {}}))

(defn- sse-event
  "Format a JSON-RPC message as an SSE event with sequential ID."
  [event-id message]
  (str "id: " event-id "\nevent: message\ndata: " (json/generate-string message) "\n\n"))

(defn push-notification!
  "Push a JSON-RPC notification to all open SSE channels for a session.
   If no SSE channel is open, queues the message for delivery when
   a client reconnects via GET /mcp.

   This is the public endpoint for server-initiated notifications
   (tools/list_changed, resources/updated, logging/message, etc.)."
  [session-store session-id message]
  (when session-id
    (let [{:keys [sessions]} @session-store
          session (get sessions session-id)
          channels (:sse-channels session)]
      (if (seq channels)
        (doseq [[ch-id {:keys [counter]}] channels]
          (let [eid (swap! counter inc)]
            (try
              (http/send! ch-id (sse-event eid message) :text)
              (catch Exception _ nil))))
        (when session
          (swap! session-store
                 update :pending-sse-messages
                 (fn [m] (update m session-id conj message))))))))

(defn drain-pending-messages!
  "Send all pending SSE messages to the given channel. Registers the channel
   in the session-store first, then drains the queue, leaving the channel
   open for future push-notification! calls."
  [session-store session-id channel]
  (let [counter (atom 0)
        pending (get (:pending-sse-messages @session-store) session-id)]
    (swap! session-store
           (fn [state]
             (-> state
                 (update-in [:sessions session-id :sse-channels]
                            assoc channel {:counter counter})
                 (update :pending-sse-messages dissoc session-id))))
    (when (seq pending)
      (doseq [msg pending
              :let [eid (swap! counter inc)]]
        (try
          (http/send! channel (sse-event eid msg) :text)
          (catch Exception _ nil))))
    counter))

(defn deregister-sse-channel!
  "Remove an SSE channel from the session store."
  [session-store session-id channel]
  (swap! session-store
         update-in [:sessions session-id :sse-channels]
         (fn [channels] (dissoc channels channel))))

(defn create-session!
  "Create a new session and return its ID."
  ([store]
   (create-session! store nil))
  ([store extra]
   (let [sid (str (java.util.UUID/randomUUID))
         now (System/currentTimeMillis)]
     (swap! store update :sessions assoc sid (merge {:created-at now :last-active now} extra))
     sid)))

(defn valid-session?
  "Check if a session ID is in the active sessions map."
  [store sid]
  (boolean (and sid (contains? (:sessions @store) sid))))

(defn terminated-session?
  "Check if a session ID has been terminated (tombstoned)."
  [store sid]
  (boolean (and sid (contains? (:terminated @store) sid))))

(defn touch-session!
  "Update the last-active timestamp for a session."
  [store sid]
  (when sid
    (swap! store update-in [:sessions sid :last-active]
           (constantly (System/currentTimeMillis)))))

(defn delete-session!
  "Terminate a session. Moves it from :sessions to :terminated (tombstone).

   Returns:
     :existed            — session was active and has been terminated
     :already-terminated — session was already in the terminated map
     :not-found          — session was never seen"
  [store sid]
  (if (nil? sid)
    :not-found
    (let [{:keys [sessions terminated]} @store
          result (cond
                   (contains? sessions sid) :existed
                   (contains? terminated sid) :already-terminated
                   :else :not-found)]
      (case result
        :existed
        (swap! store (fn [s]
                       (-> s
                           (update :sessions dissoc sid)
                           (assoc-in [:terminated sid] {:terminated-at (System/currentTimeMillis)}))))
        (:already-terminated :not-found) nil)
      result)))

(defn prune-expired-sessions!
  "Remove terminated sessions older than ttl-ms.
   Returns count of remaining terminated sessions.
   Does NOT remove active sessions — those should persist until explicitly deleted."
  ([store]
   (prune-expired-sessions! store (* 60 60 1000))) ; 1 hour default
  ([store ttl-ms]
   (let [cutoff (- (System/currentTimeMillis) ttl-ms)]
     (swap! store (fn [{:keys [terminated] :as state}]
                    (assoc state :terminated
                           (reduce-kv (fn [acc k v]
                                        (if (< (:terminated-at v) cutoff)
                                          acc
                                          (assoc acc k v)))
                                      {}
                                      terminated))))
     (count (:terminated @store)))))

(defn- start-session-sweeper!
  "Start a background daemon thread that periodically prunes expired terminated sessions.
   Returns a zero-arg stop function.

   Options:
     :interval-ms  — sleep between sweeps in ms (default: 60000)
     :ttl-ms       — session TTL in ms for prune-expired-sessions! (default: 3600000)"
  [session-store & [{:keys [interval-ms ttl-ms]
                     :or {interval-ms 60000
                          ttl-ms 3600000}}]]
  (let [running (atom true)]
    (doto (Thread. (fn []
                     (while @running
                       (try
                         (Thread/sleep interval-ms)
                         (prune-expired-sessions! session-store ttl-ms)
                         (catch InterruptedException _
                           (reset! running false))
                         (catch Exception e
                           (log-event "warn" "sweeper-error"
                                      {:error (.getMessage e)
                                       :error-class (.getName (class e))}))))))
      (.setDaemon true)
      .start)
    #(reset! running false)))

(defn- json-response
  "Create a JSON HTTP response."
  ([status body]
   (json-response status body {}))
  ([status body extra-headers]
   {:status status
    :headers (merge {"Content-Type" "application/json"} extra-headers)
    :body (json/generate-string body)}))

(defn- accepted-response
  "Create a 202 Accepted response (for notifications/responses)."
  ([]
   (accepted-response {}))
  ([extra-headers]
   {:status 202
    :headers (merge {"Content-Type" "application/json"} extra-headers)
    :body ""}))

(defn- parse-body
  "Parse the JSON body from an HTTP request."
  [request]
  (let [body-stream (:body request)
        body-str (when body-stream (slurp body-stream))]
    (when (and body-str (not (str/blank? body-str)))
      (try
        (json/parse-string body-str true)
        (catch Exception _
          :parse-error)))))

;; ─── Origin Validation ──────────────────────────────────────────────────────

(defn- validate-origin
  "Validate the Origin header against allowed origins.
   Returns true if origin is allowed or if allowed-origins is nil/empty."
  [request allowed-origins]
  (if (empty? allowed-origins)
    true
    (let [origin (find-header request "Origin")]
      (or (nil? origin)
          (contains? (set allowed-origins) origin)))))

;; ─── Dispatch Response Handling ─────────────────────────────────────────────

(defn- handle-dispatch-response
  "Handle the result of dispatching a message through the JSON-RPC handler.
   Determines if the message was a request (has :id), notification, or response,
   and returns an appropriate HTTP response.

   - For requests: returns the JSON-RPC response as JSON (200)
   - For notifications/responses: returns 202 Accepted"
  [dispatch-result new-session? session-id]
  (if (nil? dispatch-result)
    ;; Notification or pure response — 202 Accepted
    (accepted-response (when new-session? {"MCP-Session-Id" session-id}))
    ;; JSON-RPC request got a response — return as JSON
    (json-response 200 dispatch-result
                   (when new-session? {"MCP-Session-Id" session-id}))))

;; ─── Request Handlers ───────────────────────────────────────────────────────

(defn- handle-post
  "Handle POST /mcp — client→server messages.

   1. Validate Origin header
   2. Validate MCP-Protocol-Version header
   3. Parse JSON body
   4. For initialize: creates session, returns InitializeResult with MCP-Session-Id
   5. For other requests: validates session (checks terminated separately)
   6. Dispatches through the provided dispatch-fn"
  [request dispatch-fn session-store allowed-origins]
  (if-not (validate-origin request allowed-origins)
    (json-response 403
                   {:jsonrpc "2.0"
                    :error {:code -32600
                            :message "Forbidden: invalid Origin header"}})
    (if-let [version-error (validate-protocol-version request)]
      (json-response 400 version-error)
      (let [content-type (str/lower-case (or (find-header request "Content-Type") ""))]
        (if (and (not (str/blank? content-type))
                 (not (str/includes? content-type "application/json")))
          (json-response 415
                         {:jsonrpc "2.0"
                          :error {:code -32600
                                  :message "Content-Type must be application/json"}})
          (let [body (parse-body request)]
            (cond
              (nil? body)
              (json-response 400
                             {:jsonrpc "2.0"
                              :error {:code -32700
                                      :message "Parse error: request body is empty"}})

              (= body :parse-error)
              (json-response 400
                             {:jsonrpc "2.0"
                              :error {:code -32700
                                      :message "Parse error: invalid JSON"}})

              :else
              (let [session-id (find-header request "MCP-Session-Id")
                    method (:method body)
                    new-session? (= method "initialize")]
                (if new-session?
                  (let [sid (create-session! session-store)]
                    (touch-session! session-store sid)
                    (let [response (dispatch-fn body sid)]
                      (handle-dispatch-response response true sid)))
                  (cond
                    (terminated-session? session-store session-id)
                    (json-response 404
                                   {:jsonrpc "2.0"
                                    :error {:code -32600
                                            :message "Session has been terminated"}})

                    (not (valid-session? session-store session-id))
                    (json-response 400
                                   {:jsonrpc "2.0"
                                    :error {:code -32600
                                            :message "Invalid or missing MCP-Session-Id"}})

                    :else
                    (do
                      (touch-session! session-store session-id)
                      (let [response (dispatch-fn body session-id)]
                        (handle-dispatch-response response false session-id)))))))))))))

(defn- handle-get
  "Handle GET /mcp — server→client SSE stream.

   Opens an SSE stream for server-initiated messages.
   Registers the channel in the session-store so push-notification!
   can send to it. Drains any pending messages on connect."
  [request session-store allowed-origins]
  (if-not (validate-origin request allowed-origins)
    (json-response 403
                   {:jsonrpc "2.0"
                    :error {:code -32600
                            :message "Forbidden: invalid Origin header"}})
    (let [session-id (find-header request "MCP-Session-Id")]
      (cond
        (nil? session-id)
        (json-response 400
                       {:jsonrpc "2.0"
                        :error {:code -32600
                                :message "MCP-Session-Id header required for SSE stream"}})

        (terminated-session? session-store session-id)
        (json-response 404
                       {:jsonrpc "2.0"
                        :error {:code -32600
                                :message "Session has been terminated"}})

        (not (valid-session? session-store session-id))
        (json-response 404
                       {:jsonrpc "2.0"
                        :error {:code -32600
                                :message "Session not found"}})

        :else
        (http/as-channel request
                         {:on-open (fn [ch]
                                     (drain-pending-messages! session-store session-id ch))
                          :on-close (fn [ch _status]
                                      (deregister-sse-channel! session-store session-id ch))})))))

(defn- handle-delete
  "Handle DELETE /mcp — session termination.

   Returns:
     200 — session terminated successfully (or was already terminated)
     400 — no MCP-Session-Id provided
     404 — session never existed"
  [request session-store]
  (let [session-id (find-header request "MCP-Session-Id")]
    (if (nil? session-id)
      (json-response 400
                     {:jsonrpc "2.0"
                      :error {:code -32600
                              :message "MCP-Session-Id header required"}})
      (case (delete-session! session-store session-id)
        :existed
        {:status 200 :headers {} :body ""}

        :already-terminated
        {:status 200 :headers {} :body ""}

        :not-found
        (json-response 404
                       {:jsonrpc "2.0"
                        :error {:code -32600
                                :message "Session not found"}})))))

;; ─── Public API ─────────────────────────────────────────────────────────────

(defn create-handler
  "Create an http-kit handler function for Streamable HTTP transport.

   dispatch-fn: (fn [json-rpc-message session-id] response-map-or-nil)
     - response-map-or-nil: JSON-RPC response map, or nil for notifications
     - The dispatch-fn is responsible for JSON-RPC routing and execution

   Options:
     :session-store    — atom for session management (default: creates new one)
     :allowed-origins  — vector of allowed Origin headers (default: [] = allow all)

   Returns a function suitable for http-kit/run-server.

   Note: If you use `create-handler` directly (not via `run-server`),
   session pruning is NOT automatic. Call `prune-expired-sessions!`
   periodically to reclaim terminated sessions:
     (prune-expired-sessions! session-store session-ttl-ms)
   Use `run-server` for automatic lifecycle management (sweeper + shutdown hook)."
  [dispatch-fn & [{:keys [session-store allowed-origins]
                   :or {session-store (create-session-store)
                        allowed-origins []}}]]
  (fn [request]
    (with-request-logging
      (fn [req]
        (let [uri (:uri req)]
          (cond
            (or (= uri "/mcp") (= uri "/mcp/"))
            (case (:request-method req)
              :post (handle-post req dispatch-fn session-store allowed-origins)
              :get (handle-get req session-store allowed-origins)
              :delete (handle-delete req session-store)
              {:status 405
               :headers {"Content-Type" "text/plain"}
               :body "Method Not Allowed"})

            (= uri "/health")
            {:status 200
             :headers {"Content-Type" "application/json"}
             :body (json/generate-string {:status "ok"})}

            :else
            {:status 404
             :headers {"Content-Type" "text/plain"}
             :body "Not Found"})))
      request)))

(defn run-server
  "Start an MCP server with Streamable HTTP transport and
   lifecycle management (shutdown hook, session sweeper).

   dispatch-fn: (fn [json-rpc-message session-id] response-map-or-nil)
   Options:
     :port              — HTTP port (default: 0 = random available port)
     :host              — HTTP host (default: \"127.0.0.1\")
     :session-store     — atom for session management
     :allowed-origins   — vector of allowed Origin headers
     :session-ttl-ms    — TTL for terminated sessions in ms (default: 3600000)
     :sweeper-interval  — session sweep interval in ms (default: 60000)
     :shutdown-timeout  — graceful shutdown timeout in ms (default: 5000)
     :auto-shutdown?    — register JVM shutdown hook (default: true)

   Returns the http-kit server stop fn."
  [dispatch-fn & [{:keys [port host session-store session-ttl-ms sweeper-interval shutdown-timeout auto-shutdown?]
                   :or {port 0 host "127.0.0.1"
                        session-ttl-ms 3600000
                        sweeper-interval 60000
                        shutdown-timeout 5000
                        auto-shutdown? true}
                   :as opts}]]
  (let [session-store (or session-store (create-session-store))
        handler (create-handler dispatch-fn (assoc opts :session-store session-store))
        stop-server (http/run-server handler {:port port :ip host})]
    (start-session-sweeper! session-store
                            {:interval-ms sweeper-interval
                             :ttl-ms session-ttl-ms})
    (when auto-shutdown?
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. (fn []
                                   (log-event "info" "server-shutdown"
                                              {:reason "JVM shutdown hook"})
                                   (stop-server shutdown-timeout)))))
    stop-server))
