# mcp-toolkit

> Fork of [metosin/mcp-toolkit](https://github.com/metosin/mcp-toolkit) with Streamable HTTP transport,
> Babashka runtime support, and plugin registry.

An unofficial MCP SDK for Clojure. Handles MCP client-server communication with a
Clojure-idiomatic API. Targets protocol version **2025-11-25** (Streamable HTTP).

## Fork Additions

Upstream supports STDIO and SSE. This fork adds:

| Feature | Status |
|---------|--------|
| Streamable HTTP (POST/GET/DELETE, session mgmt) | Done |
| Babashka runtime (CompletableFuture shim) | Done |
| CLJS reader conditionals in core | Done |
| Plugin registry (O(1) tool index, collision detection) | Done |
| Request timeouts (per-tool) | Done |
| Graceful shutdown (SIGTERM handler with drain) | Done |
| Structured logging (request-id, duration, JSON output) | Done |
| Session TTL sweeper (auto-cleanup) | Done |
| Content-Type enforcement | Done |
| Integration tests (real http-kit lifecycle) | Done |
| Nix flake (dev shell + deps lockfile) | Done |

## Quickstart

### Add dependency

deps.edn (JVM):
```clojure
{:deps {com.noblepayne/mcp-toolkit
        {:git/url "https://github.com/noblepayne/mcp-toolkit"
         :git/sha "a2836d3"}}}
```

bb.edn (Babashka):
```clojure
{:paths ["src"]
 :deps {com.noblepayne/mcp-toolkit
        {:git/url "https://github.com/noblepayne/mcp-toolkit"
         :git/sha "a2836d3"}}}
```

### Minimal server (Babashka)

The transport dispatch function receives `(json-rpc-message, session-id)` and
returns a response map (or nil for notifications):

```clojure
(ns my-server
  (:require [mcp-toolkit.transport.streamable-http :as http]))

(defn dispatch [msg sid]
  (let [m (:method msg)]
    (case m
      "initialize"
      {:jsonrpc "2.0" :id (:id msg)
       :result {:serverName "my-server" :protocolVersion "2025-11-25"
                :capabilities {:tools {:listChanged true}}}}
      "tools/list"
      {:jsonrpc "2.0" :id (:id msg)
       :result {:tools [{:name "hello" :description "Say hello"
                         :inputSchema {:type "object"
                                       :properties {:name {:type "string"}}
                                       :required []}}]}}
      "tools/call"
      {:jsonrpc "2.0" :id (:id msg)
       :result {:content [{:type "text"
                           :text (str "Hello " (-> msg :params :arguments :name "world"))}]}}
      nil)))

(defn -main [& args]
  (let [stop (http/run-server dispatch {:port 8080})]
    (println "Server running on http://localhost:8080/mcp")
    @(promise))) ;; block forever

;; bb -m my-server
```

For a server using the library's full handler API (tools, prompts, resources
with the MCP initialization handshake), see the `example/` directory.

### Server options

```clojure
(http/run-server dispatch-fn
  {:port             8080               ;; default 0 (random available)
   :host             "0.0.0.0"          ;; default "127.0.0.1"
   :session-store    (create-session-store)  ;; custom store (optional)
   :allowed-origins  ["https://app.example.com"]  ;; origin check
   :session-ttl-ms   3600000            ;; expire terminated sessions after 1h
   :sweeper-interval 60000              ;; sweep for stale sessions every 60s
   :shutdown-timeout 5000               ;; wait 5s for in-flight on shutdown
   :auto-shutdown?   true})             ;; register JVM shutdown hook
```

### Custom logging

Bind `*log-fn*` as a dynamic var:

```clojure
(binding [http/*log-fn* (fn [level event data]
                          (println level ":" event))]
  (http/run-server dispatch {:port 8080}))
```

Per-tool request timeouts use the `:timeout` field on the tool registration:

```clojure
{:name "slow-tool" :timeout 5000 :tool-fn slow-fn}
```

## Plugin Registry

For servers with tools from multiple sources:

```clojure
(require '[mcp-toolkit.registry :as reg])

(def r (reg/create-registry))

(reg/register! r
  {:name "search"
   :tools [{:name "search" :tool-fn search-fn}
           {:name "lookup" :tool-fn lookup-fn}]})

;; pass :registry to mcp-toolkit.server/create-session
(def session (server/create-session {:registry r}))
```

## Babashka Compatibility

Everything in `src/` loads on bb without modification:
- `streamable_http.clj` -- bb includes http-kit, cheshire, java.time.Instant
- Promise shim uses `java.util.concurrent.CompletableFuture` (no promesa dep)
- `clojure.tools.logging` works on bb with no extra config

Run the smoke tests:
```shell
bb test/mcp_toolkit/smoke_test.bb
```

## Nix

```shell
nix develop          # dev shell with jdk21, clojure, bb, clj-kondo, cljfmt, jet
nix run .#deps-lock  # regenerate deps-lock.json after changing deps.edn
```

## Testing & Linting

```shell
clojure -M:test              # JVM tests (87 tests, kaocha)
bb test/mcp_toolkit/smoke_test.bb  # Babashka smoke tests (8 tests)
clj-kondo --lint src/        # lint
cljfmt fix src/ test/        # format
```

## MCP Feature Status

- [x] Streamable HTTP (2025-11-25)
- [x] STDIO
- [x] SSE (skeleton -- open an issue if you need it)
- [x] Tools, Prompts, Resources
- [x] Cancellation, Ping, Progress
- [x] Roots, Sampling
- [x] Completion, Logging
- [ ] Pagination

## License

EPL v2.0. Copyright (c) [Metosin](https://metosin.fi) and contributors (upstream)
with additions by [noblepayne](https://github.com/noblepayne) (fork).
