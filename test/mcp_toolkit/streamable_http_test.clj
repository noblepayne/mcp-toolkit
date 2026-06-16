(ns mcp-toolkit.streamable-http-test
  "HTTP integration tests for streamable_http transport.
   Tests POST/GET/DELETE cycles, session management, protocol versioning,
   origin validation, and session lifecycle per MCP 2025-11-25 spec."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [mcp-toolkit.test.fixtures :as fix]
            [mcp-toolkit.transport.streamable-http :as transport]
            [org.httpkit.client :as http]
            [cheshire.core :as json]))

;; Helper: initialize handler returns proper JSON-RPC response
(defn- init-dispatcher
  []
  (fn [msg _sid]
    {:jsonrpc "2.0"
     :id (:id msg)
     :result {:protocolVersion "2025-11-25"
              :capabilities {:tools {:listChanged true}}
              :serverInfo {:name "test-server" :version "0.1.0"}}}))

;; ─── POST /mcp Tests ──────────────────────────────────────────────────────

(deftest initialize-creates-session
  (testing "POST with initialize returns 200 + MCP-Session-Id"
    (let [store (transport/create-session-store)
          handler (transport/create-handler (init-dispatcher) {:session-store store})
          resp (fix/post-json handler "/mcp"
                              {:jsonrpc "2.0"
                               :method "initialize"
                               :id 1
                               :params {:protocolVersion "2025-11-25"}})
          session-id (get-in resp [:headers "MCP-Session-Id"])]
      (is (= 200 (:status resp)))
      (is (string? session-id))
      (is (> (count session-id) 10)))))

(deftest post-with-invalid-origin
  (testing "POST with bad Origin returns 403"
    (let [handler (transport/create-handler (init-dispatcher)
                                            {:allowed-origins ["https://example.com"]})
          resp (fix/handler-response handler :post "/mcp"
                                     {:headers {"Content-Type" "application/json"
                                                "Origin" "https://evil.com"}
                                      :body {:jsonrpc "2.0"
                                             :method "initialize"
                                             :id 1
                                             :params {}}})]
      (is (= 403 (:status resp))))))

(deftest post-with-missing-body
  (testing "POST with no body returns 400"
    (let [handler (transport/create-handler (fn [_ _] nil))
          resp (fix/handler-response handler :post "/mcp"
                                     {:headers {"Content-Type" "application/json"}}
                                     nil)]
      (is (= 400 (:status resp))))))

(deftest post-with-invalid-json
  (testing "POST with invalid JSON returns 400"
    (let [handler (transport/create-handler (fn [_ _] nil))
          resp (fix/handler-response handler :post "/mcp"
                                     {:headers {"Content-Type" "application/json"}}
                                     "NOT VALID")]
      (is (= 400 (:status resp))))))

(deftest post-with-unknown-session-id
  (testing "POST with unknown session-id returns 400"
    (let [handler (transport/create-handler (fn [_ _] nil))
          resp (fix/handler-response handler :post "/mcp"
                                     {:headers {"Content-Type" "application/json"
                                                "MCP-Session-Id" "nope"}
                                      :body {:jsonrpc "2.0"
                                             :method "tools/list"
                                             :id 2}})]
      (is (= 400 (:status resp))))))

(deftest post-notification-returns-202
  (testing "POST with notification (no :id) returns 202"
    (let [store (transport/create-session-store)
          handler (transport/create-handler (fn [_ _] nil) {:session-store store})
          resp1 (fix/post-json handler "/mcp"
                               {:jsonrpc "2.0"
                                :method "initialize"
                                :id 1
                                :params {:protocolVersion "2025-11-25"}})
          sid (get-in resp1 [:headers "MCP-Session-Id"])
          resp2 (fix/handler-response handler :post "/mcp"
                                      {:headers {"Content-Type" "application/json"
                                                 "MCP-Session-Id" sid}
                                       :body {:jsonrpc "2.0"
                                              :method "notifications/initialized"}})]
      (is sid)
      (is (= 202 (:status resp2))))))

;; ─── DELETE /mcp Tests ──────────────────────────────────────────────────

(deftest delete-session-success
  (testing "DELETE valid session returns 200"
    (let [store (transport/create-session-store)
          handler (transport/create-handler (init-dispatcher) {:session-store store})
          resp1 (fix/post-json handler "/mcp"
                               {:jsonrpc "2.0" :method "initialize" :id 1
                                :params {:protocolVersion "2025-11-25"}})
          sid (get-in resp1 [:headers "MCP-Session-Id"])
          resp2 (fix/delete-request handler "/mcp" {"MCP-Session-Id" sid})]
      (is (= 200 (:status resp1)))
      (is (= 200 (:status resp2))))))

(deftest delete-already-terminated-session
  (testing "DELETE already-terminated returns 200"
    (let [store (transport/create-session-store)
          handler (transport/create-handler (init-dispatcher) {:session-store store})
          resp1 (fix/post-json handler "/mcp"
                               {:jsonrpc "2.0" :method "initialize" :id 1
                                :params {:protocolVersion "2025-11-25"}})
          sid (get-in resp1 [:headers "MCP-Session-Id"])
          _ (fix/delete-request handler "/mcp" {"MCP-Session-Id" sid})
          resp2 (fix/delete-request handler "/mcp" {"MCP-Session-Id" sid})]
      (is (= 200 (:status resp2))))))

(deftest delete-nonexistent-session
  (testing "DELETE nonexistent session returns 404"
    (let [handler (transport/create-handler (fn [_ _] nil))
          resp (fix/delete-request handler "/mcp" {"MCP-Session-Id" "nope"})]
      (is (= 404 (:status resp))))))

(deftest delete-without-session-id
  (testing "DELETE without session-id returns 400"
    (let [handler (transport/create-handler (fn [_ _] nil))
          resp (fix/delete-request handler "/mcp")]
      (is (= 400 (:status resp))))))

;; ─── POST After DELETE Tests ────────────────────────────────────────────

(deftest post-after-delete-returns-404
  (testing "POST with terminated session returns 404"
    (let [store (transport/create-session-store)
          handler (transport/create-handler (init-dispatcher) {:session-store store})
          resp1 (fix/post-json handler "/mcp"
                               {:jsonrpc "2.0" :method "initialize" :id 1
                                :params {:protocolVersion "2025-11-25"}})
          sid (get-in resp1 [:headers "MCP-Session-Id"])
          _ (fix/delete-request handler "/mcp" {"MCP-Session-Id" sid})
          resp2 (fix/handler-response handler :post "/mcp"
                                      {:headers {"Content-Type" "application/json"
                                                 "MCP-Session-Id" sid}
                                       :body {:jsonrpc "2.0" :method "tools/list" :id 2}})]
      (is (= 404 (:status resp2))))))

;; ─── GET /mcp Tests ─────────────────────────────────────────────────────

(deftest get-without-session-id
  (testing "GET without session-id returns 400"
    (let [handler (transport/create-handler (fn [_ _] nil))
          resp (fix/get-request handler "/mcp")]
      (is (= 400 (:status resp))))))

(deftest get-with-invalid-session
  (testing "GET with invalid session returns 404"
    (let [handler (transport/create-handler (fn [_ _] nil))
          resp (fix/get-request handler "/mcp" {"MCP-Session-Id" "nope"})]
      (is (= 404 (:status resp))))))

(deftest get-with-terminated-session
  (testing "GET with terminated session returns 404"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)
          _ (transport/delete-session! store sid)
          _ (transport/create-session! store)
          handler (transport/create-handler (fn [_ _] nil) {:session-store store})
          resp (fix/handler-response handler :get "/mcp"
                                     {:headers {"MCP-Session-Id" sid}})]
      (is (= 404 (:status resp))))))

;; ─── Health / 405 / 404 ─────────────────────────────────────────────────

(deftest health-endpoint
  (testing "GET /health returns 200 ok"
    (let [resp (fix/handler-response
                (transport/create-handler (fn [_ _] nil))
                :get "/health")]
      (is (= 200 (:status resp)))
      (is (re-find #"ok" (:body resp))))))

(deftest unsupported-method-on-mcp
  (testing "PUT /mcp returns 405"
    (let [resp (fix/handler-response
                (transport/create-handler (fn [_ _] nil))
                :put "/mcp")]
      (is (= 405 (:status resp))))))

(deftest unknown-uri
  (testing "Unknown URI returns 404"
    (let [resp (fix/handler-response
                (transport/create-handler (fn [_ _] nil))
                :get "/unknown")]
      (is (= 404 (:status resp))))))

;; ─── Session Store Lifecycle Tests ──────────────────────────────────────

(deftest create-session-store-structure
  (testing "create-session-store returns {:sessions {} :terminated {} :pending-sse-messages {}}"
    (let [store (transport/create-session-store)]
      (is (= {:sessions {} :terminated {} :pending-sse-messages {}} @store)))))

(deftest create-session!-adds-to-sessions
  (testing "create-session! adds to :sessions"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)]
      (is (contains? (:sessions @store) sid)))))

(deftest valid-session?-checks-sessions
  (testing "valid-session? checks :sessions"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)]
      (is (transport/valid-session? store sid))
      (is (not (transport/valid-session? store "nope"))))))

(deftest terminated-session?-checks-terminated
  (testing "terminated-session? detects tombstoned sessions"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)]
      (is (not (transport/terminated-session? store sid)))
      (transport/delete-session! store sid)
      (is (transport/terminated-session? store sid)))))

(deftest delete-session!-returns-keywords
  (testing "delete-session! returns proper keywords"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)]
      (is (= :existed (transport/delete-session! store sid)))
      (is (= :already-terminated (transport/delete-session! store sid)))
      (is (= :not-found (transport/delete-session! store "nope")))
      (is (= :not-found (transport/delete-session! store nil))))))

(deftest delete-session-atomic-tombstone
  (testing "delete moves session to terminated"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)]
      (is (contains? (:sessions @store) sid))
      (transport/delete-session! store sid)
      (is (not (contains? (:sessions @store) sid)))
      (is (contains? (:terminated @store) sid)))))

(deftest prune-expired-sessions-works
  (testing "prune-expired-sessions! removes terminated under TTL"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)]
      (transport/delete-session! store sid)
      (swap! store assoc-in [:terminated sid :terminated-at] 0)
      (transport/prune-expired-sessions! store 1000)
      (is (empty? (:terminated @store))))))

;; ─── SSE Channel Tests ───────────────────────────────────────────────────────

(deftest push-notification-queues-when-no-channel
  (testing "push-notification! queues messages when no SSE channel open"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)
          msg {:jsonrpc "2.0" :method "notifications/tools/list_changed"}]
      (transport/push-notification! store sid msg)
      (is (= [msg] (get-in @store [:pending-sse-messages sid])))
      (is (nil? (get-in @store [:sessions sid :sse-channels]))) "sse-channels should be nil when no channel registered")))

(deftest push-notification-sends-to-open-channel
  (testing "push-notification! sends to open channels when available"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)
          msg {:jsonrpc "2.0" :method "notifications/tools/list_changed"}
          mock-channel (Object.)
          counter (atom 0)]
      (swap! store update-in [:sessions sid :sse-channels] assoc mock-channel {:counter counter})
      (transport/push-notification! store sid msg)
      (is (= 1 @counter))
      (is (nil? (get-in @store [:pending-sse-messages sid]))))))

(deftest push-notification-handles-unknown-session
  (testing "push-notification! is safe with unknown session-id"
    (let [store (transport/create-session-store)]
      (is (nil? (transport/push-notification! store "unknown-session" {})))
      (is (nil? (transport/push-notification! store nil {}))))))

(deftest drain-pending-messages-registers-channel
  (testing "drain-pending-messages! registers channel and drains queue"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)
          msg {:jsonrpc "2.0" :method "notifications/tools/list_changed"}
          mock-channel (Object.)]
      (swap! store update :pending-sse-messages assoc sid [msg])
      (let [counter (transport/drain-pending-messages! store sid mock-channel)]
        (is (contains? (get-in @store [:sessions sid :sse-channels]) mock-channel))
        (is (some? counter))
        (is (nil? (get-in @store [:pending-sse-messages sid])))))))

(deftest drain-pending-messages-handles-empty-queue
  (testing "drain-pending-messages! works when queue is empty"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)
          mock-channel (Object.)]
      (is (some? (transport/drain-pending-messages! store sid mock-channel)))
      (is (contains? (get-in @store [:sessions sid :sse-channels]) mock-channel)))))

(deftest deregister-sse-channel-removes-channel
  (testing "deregister-sse-channel! removes channel from session"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)
          mock-channel (Object.)]
      (swap! store update-in [:sessions sid :sse-channels] assoc mock-channel {:counter (atom 0)})
      (is (contains? (get-in @store [:sessions sid :sse-channels]) mock-channel))
      (transport/deregister-sse-channel! store sid mock-channel)
      (is (not (contains? (get-in @store [:sessions sid :sse-channels]) mock-channel))))))

(deftest push-notification-round-trip
  (testing "Full round-trip: queue message, connect SSE, drain, then push more"
    (let [store (transport/create-session-store)
          sid (transport/create-session! store)
          msg1 {:jsonrpc "2.0" :method "notifications/tools/list_changed"}
          msg2 {:jsonrpc "2.0" :method "notifications/resources/list_changed"}
          mock-channel (Object.)]
      (transport/push-notification! store sid msg1)
      (transport/push-notification! store sid msg2)
      (is (= 2 (count (get-in @store [:pending-sse-messages sid]))))
      (transport/drain-pending-messages! store sid mock-channel)
      (is (nil? (get-in @store [:pending-sse-messages sid])))
      (is (contains? (get-in @store [:sessions sid :sse-channels]) mock-channel))
      (transport/push-notification! store sid msg1)
      (is (nil? (get-in @store [:pending-sse-messages sid]))))))

;; ─── Real HTTP Integration Tests ─────────────────────────────────────────────

(defn- url-for
  "Build the full MCP endpoint URL for a given port."
  [port]
  (str "http://127.0.0.1:" port "/mcp"))

(defn- url-for-health
  "Build the health endpoint URL."
  [port]
  (str "http://127.0.0.1:" port "/health"))

(defn- parse-json-body
  "Parse JSON string response body."
  [resp]
  (when (:body resp)
    (try (json/parse-string (:body resp) true)
         (catch Exception _ (:body resp)))))

(deftest integration-full-lifecycle
  (testing "Real HTTP lifecycle: init → tools/list → tools/call → delete"
    (fix/with-http-server
      (fn [{:keys [port]}]
        (let [mcp (url-for port)
              opts {:as :text
                    :headers {"Content-Type" "application/json"
                              "MCP-Protocol-Version" "2025-11-25"}}
              init @(http/post mcp (assoc opts
                                          :body (json/generate-string
                                                 {:jsonrpc "2.0" :method "initialize" :id 1
                                                  :params {:protocolVersion "2025-11-25"}})))
              session-id (get-in init [:headers :mcp-session-id])]
          (is (= 200 (:status init)))
          (is (string? session-id))
          (is (> (count session-id) 10))
          (is (= "2025-11-25" (get-in (parse-json-body init) [:result :protocolVersion])))

          (let [auth-opts {:as :text
                           :headers {"Content-Type" "application/json"
                                     "MCP-Session-Id" session-id}}
                tools @(http/post mcp (assoc auth-opts
                                             :body (json/generate-string
                                                    {:jsonrpc "2.0" :method "tools/list" :id 2})))]
            (is (= 200 (:status tools)))
            (is (= [] (get-in (parse-json-body tools) [:result :tools]))))

          (let [auth-opts {:as :text
                           :headers {"Content-Type" "application/json"
                                     "MCP-Session-Id" session-id}}
                call @(http/post mcp (assoc auth-opts
                                            :body (json/generate-string
                                                   {:jsonrpc "2.0" :method "tools/call" :id 3
                                                    :params {:name "test" :arguments {:x 1}}})))]
            (is (= 200 (:status call)))
            (is (= "Called test with {:x 1}" (get-in (parse-json-body call) [:result :content 0 :text]))))

          (let [del @(http/delete mcp {:headers {"MCP-Session-Id" session-id}})]
            (is (= 200 (:status del)))))))))

(defn- init-session
  "Helper: initialize a session over HTTP, return session-id."
  [mcp]
  (let [resp @(http/post mcp {:as :text
                              :headers {"Content-Type" "application/json"
                                        "MCP-Protocol-Version" "2025-11-25"}
                              :body (json/generate-string
                                     {:jsonrpc "2.0" :method "initialize" :id 1
                                      :params {:protocolVersion "2025-11-25"}})})
        sid (get-in resp [:headers :mcp-session-id])]
    (is (= 200 (:status resp)))
    (is (string? sid))
    sid))

(deftest integration-terminated-session-rejected
  (testing "POST after DELETE returns 404 with JSON-RPC error"
    (fix/with-http-server
      (fn [{:keys [port]}]
        (let [mcp (url-for port)
              session-id (init-session mcp)
              _ @(http/delete mcp {:headers {"MCP-Session-Id" session-id}})
              tools @(http/post mcp {:as :text
                                     :headers {"Content-Type" "application/json"
                                               "MCP-Session-Id" session-id}
                                     :body (json/generate-string
                                            {:jsonrpc "2.0" :method "tools/list" :id 2})})
              body (parse-json-body tools)]
          (is (= 404 (:status tools)))
          (is (= {:code -32600 :message "Session has been terminated"}
                 (:error body))))))))

(deftest integration-content-type-enforcement
  (testing "POST with wrong Content-Type returns 415"
    (fix/with-http-server
      (fn [{:keys [port]}]
        (let [resp @(http/post (url-for port)
                               {:as :text
                                :headers {"Content-Type" "text/plain"}
                                :body "{}"})
              body (parse-json-body resp)]
          (is (= 415 (:status resp)))
          (is (= {:code -32600 :message "Content-Type must be application/json"}
                 (:error body))))))))

(deftest integration-bad-protocol-version
  (testing "POST with unsupported protocol version returns 400"
    (fix/with-http-server
      (fn [{:keys [port]}]
        (let [resp @(http/post (url-for port)
                               {:as :text
                                :headers {"Content-Type" "application/json"
                                          "MCP-Protocol-Version" "2099-01-01"}
                                :body (json/generate-string
                                       {:jsonrpc "2.0" :method "initialize" :id 1
                                        :params {:protocolVersion "2099-01-01"}})})
              body (parse-json-body resp)]
          (is (= 400 (:status resp)))
          (is (str/includes? (get-in body [:error :message]) "Unsupported protocol version")))))))

(deftest integration-missing-body
  (testing "POST with no body returns 400 with JSON-RPC error"
    (fix/with-http-server
      (fn [{:keys [port]}]
        (let [resp @(http/post (url-for port)
                               {:as :text
                                :headers {"Content-Type" "application/json"}
                                :body ""})
              body (parse-json-body resp)]
          (is (= 400 (:status resp)))
          (is (= {:code -32700 :message "Parse error: request body is empty"}
                 (:error body))))))))

(deftest integration-invalid-session
  (testing "POST with unknown session returns 400"
    (fix/with-http-server
      (fn [{:keys [port]}]
        (let [resp @(http/post (url-for port)
                               {:as :text
                                :headers {"Content-Type" "application/json"
                                          "MCP-Session-Id" "nonsense"}
                                :body (json/generate-string
                                       {:jsonrpc "2.0" :method "tools/list" :id 1})})
              body (parse-json-body resp)]
          (is (= 400 (:status resp)))
          (is (= {:code -32600 :message "Invalid or missing MCP-Session-Id"}
                 (:error body))))))))

(deftest integration-health-check
  (testing "GET /health returns 200 ok"
    (fix/with-http-server
      (fn [{:keys [port]}]
        (let [resp @(http/get (url-for-health port) {:as :text})]
          (is (= 200 (:status resp)))
          (is (= "ok" (get (parse-json-body resp) :status))))))))
