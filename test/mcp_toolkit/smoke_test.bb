(ns smoke-test
  (:require [mcp-toolkit.transport.streamable-http :as http]
            [org.httpkit.client :as client]
            [cheshire.core :as json]))

(defn dispatcher [msg sid]
  (let [m (:method msg)]
    (case m
      "initialize" {:jsonrpc "2.0" :id (:id msg) :result {:serverName "test" :protocolVersion "2025-11-25"}}
      "tools/list" {:jsonrpc "2.0" :id (:id msg) :result {:tools [{:name "test-tool" :inputSchema {"type" "object"}}]}}
      "tools/call" {:jsonrpc "2.0" :id (:id msg) :result {:content [{:type "text" :text "ok"}]}}
      nil)))

(def passed (atom 0))
(def total (atom 0))

(defn test [name thunk]
  (swap! total inc)
  (try
    (thunk)
    (swap! passed inc)
    (println "✓" name)
    (catch Throwable e
      (println "✗" name ":" (.getMessage e)))))

(def server (http/run-server dispatcher {:port 0 :host "127.0.0.1"}))
(def port (-> server meta :local-port))
(println "Server on port:" port)
(Thread/sleep 100)

(test "initialize returns 200 + session-id"
  (fn []
    (let [resp @(client/post (str "http://127.0.0.1:" port "/mcp")
                    {:headers {"Content-Type" "application/json"
                               "MCP-Protocol-Version" "2025-11-25"}
                     :body (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize"})})]
      (assert (= 200 (:status resp)))
      (assert (get (:headers resp) :mcp-session-id)))))

(def session-id
  (let [resp @(client/post (str "http://127.0.0.1:" port "/mcp")
                  {:headers {"Content-Type" "application/json"
                             "MCP-Protocol-Version" "2025-11-25"}
                   :body (json/generate-string {:jsonrpc "2.0" :id 1 :method "initialize"})})]
    (get (:headers resp) :mcp-session-id)))

(test "tools/list returns tool list"
  (fn []
    (let [resp @(client/post (str "http://127.0.0.1:" port "/mcp")
                    {:headers {"Content-Type" "application/json"
                               "MCP-Session-Id" session-id}
                     :body (json/generate-string {:jsonrpc "2.0" :id 2 :method "tools/list"})})
          body (json/parse-string (:body resp) true)]
      (assert (= 200 (:status resp)))
      (assert (get-in body [:result :tools])))))

(test "tools/call returns result"
  (fn []
    (let [resp @(client/post (str "http://127.0.0.1:" port "/mcp")
                    {:headers {"Content-Type" "application/json"
                               "MCP-Session-Id" session-id}
                     :body (json/generate-string {:jsonrpc "2.0" :id 3 :method "tools/call"
                                                  :params {:name "test-tool" :arguments {}}})})
          body (json/parse-string (:body resp) true)]
      (assert (= 200 (:status resp)))
      (assert (get-in body [:result :content])))))

(test "DELETE /mcp returns 200"
  (fn []
    (let [resp @(client/delete (str "http://127.0.0.1:" port "/mcp")
                    {:headers {"MCP-Session-Id" session-id}})]
      (assert (= 200 (:status resp))))))

(test "terminated session returns 404"
  (fn []
    (let [resp @(client/post (str "http://127.0.0.1:" port "/mcp")
                    {:headers {"Content-Type" "application/json"
                               "MCP-Session-Id" session-id}
                     :body (json/generate-string {:jsonrpc "2.0" :id 4 :method "tools/list"})})]
      (assert (= 404 (:status resp))))))

(test "GET /health returns 200"
  (fn []
    (let [resp @(client/get (str "http://127.0.0.1:" port "/health"))]
      (assert (= 200 (:status resp))))))

(test "missing session-id returns 400"
  (fn []
    (let [resp @(client/post (str "http://127.0.0.1:" port "/mcp")
                    {:headers {"Content-Type" "application/json"}
                     :body (json/generate-string {:jsonrpc "2.0" :id 5 :method "tools/list"})})]
      (assert (= 400 (:status resp))))))

(test "bad protocol version returns 400"
  (fn []
    (let [resp @(client/post (str "http://127.0.0.1:" port "/mcp")
                    {:headers {"Content-Type" "application/json"
                               "MCP-Protocol-Version" "2099-01-01"}
                     :body (json/generate-string {:jsonrpc "2.0" :id 6 :method "initialize"})})]
      (assert (= 400 (:status resp))))))

(server :timeout 100)
(println (str "\n" @passed "/" @total " tests passed"))
(System/exit (if (= @passed @total) 0 1))
