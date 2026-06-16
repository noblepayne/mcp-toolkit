# mcp-toolkit

> **Fork of [metosin/mcp-toolkit](https://github.com/metosin/mcp-toolkit)** with Streamable HTTP transport,
> cross-runtime support (JVM + Babashka + CLJS), plugin registry, structured logging,
> and production readiness hardening.

An unofficial MCP SDK in Clojure. Handles MCP client↔server communication with a
Clojure-idiomatic API, targeting the **2025-11-25** protocol version (Streamable HTTP).

## Why This Fork?

The upstream library supports STDIO and SSE. This fork adds:

| Feature | Status |
|---------|--------|
| **Streamable HTTP** (MCP 2025-11-25) | ✅ Built-in — POST/GET/DELETE, session management |
| **Babashka** runtime | ✅ Full bb support — native CompletableFuture shim |
| **CLJS** runtime | ✅ Reader conditionals in protocol core |
| **Plugin registry** | ✅ O(1) tool index, collision detection, Malli validation |
| **Request timeouts** | ✅ Per-tool configurable timeout |
| **Graceful shutdown** | ✅ SIGTERM handler with drain |
| **Structured logging** | ✅ Request-id, duration, JSON output |
| **Session TTL sweeper** | ✅ Configurable auto-cleanup of stale sessions |
| **Content-Type enforcement** | ✅ POST /mcp requires `application/json` |
| **Integration tests** | ✅ Real http-kit lifecycle tests |
| **Nix flake** | ✅ Reproducible dev shell + deps lockfile |

## Quickstart

### Add as a dependency

**deps.edn** (JVM):
```clojure
{:deps {com.noblepayne/mcp-toolkit
        {:git/url "https://github.com/noblepayne/mcp-toolkit"
         :git/sha "5edcb8e"}}}
```

**bb.edn** (Babashka):
```clojure
{:paths ["src"]
 :deps {com.noblepayne/mcp-toolkit
        {:git/url "https://github.com/noblepayne/mcp-toolkit"
         :git/sha "5edcb8e"}}}
```

### Minimal Streamable HTTP server (Babashka)

```clojure
(ns my-server
  (:require [mcp-toolkit.transport.streamable-http :as http]
            [mcp-toolkit.impl.server.handler :as handler]))

(defn my-tool [ctx args]
  {:content [{:type "text" :text (str "Hello, " (:name args "world"))}]})

(def session
  (handler/create-session
    {:tools [{:name "greet"
              :input-schema {:type "object"
                             :properties {:name {:type "string"}}
                             :required []}
              :tool-fn my-tool}]}))

(http/start-server!
  {:handler-fn (fn [msg sid]
                 (handler/handle-message session msg sid))
   :port 8080})
```

Run it:
```shell
bb my_server.clj
```

Then connect any MCP client — Claude Code, BoostBridge, etc. — to `http://localhost:8080/mcp`.

### Minimal server (JVM)

Same API, just run with `clojure -M` instead of `bb`. The transport, handler, and
registry namespaces are cross-runtime.

## Streamable HTTP Transport

The headline transport. See the [2025-11-25 spec](https://spec.modelcontextprotocol.io/2025-11-25/).

- `POST /mcp` — JSON-RPC request/response (initialize, tools/list, tools/call, etc.)
- `GET /health` — health check
- `DELETE /mcp` — session termination
- Sessions tracked by `MCP-Session-Id` header
- Configurable request logging, timeouts, session TTL

### Server options

```clojure
(http/start-server!
  {:handler-fn     (fn [msg sid] ...)  ;; message dispatcher
   :port           8080                ;; default 8080
   :host           "0.0.0.0"           ;; default "localhost"
   :max-sessions   100                 ;; optional session limit
   :shutdown-timeout 5000              ;; ms to wait for in-flight requests
   :session-ttl-ms 3600000             ;; auto-expire sessions (1h)
   :sweeper-interval-ms 60000          ;; sweep frequency
   :request-timeout-ms 30000           ;; default tool timeout
   :log-fn         (fn [event] ...)})  ;; custom logger
```

## Plugin Registry

For servers with multiple plugin modules (tools from different sources):

```clojure
(require '[mcp-toolkit.registry :as reg])

(def r (reg/create-registry))

;; Register plugins with their tools
(reg/register! r
  {:name "search"
   :tools [{:name "search" :tool-fn search-fn ...}
           {:name "lookup" :tool-fn lookup-fn ...}]})

(reg/register! r
  {:name "graph"
   :tools [{:name "graph-expand" :tool-fn expand-fn ...}]})

;; Pass :registry to create-session instead of :tools
(def session (handler/create-session {:registry r}))
```

## Babashka Compatibility

Everything in `src/` loads cleanly on bb:
- `streamable_http.clj` — bb has http-kit, cheshire, and `java.time.Instant` built in
- Promise shim uses `java.util.concurrent.CompletableFuture` (no promesa needed)
- `clojure.tools.logging` works on bb with no extra deps

Run the smoke tests:
```shell
bb test/mcp_toolkit/smoke_test.bb
```

## Nix Development Shell

```shell
nix develop          # jdk21 + clojure + bb + clj-kondo + cljfmt + jet
nix run .#deps-lock  # regenerate deps-lock.json after changing deps.edn
```

## Testing

```shell
# JVM tests (kaocha)
clojure -M:test

# Babashka smoke tests
bb test/mcp_toolkit/smoke_test.bb

# Lint
clj-kondo --lint src/

# Format
cljfmt fix src/ test/
```

## Implemented MCP Features

- [x] Streamable HTTP transport (2025-11-25)
- [x] STDIO transport
- [x] SSE transport (skeleton — contributions welcome)
- [x] Tools, Prompts, Resources
- [x] Cancellation, Ping, Progress
- [x] Roots, Sampling
- [x] Completion, Logging
- [ ] Pagination

## Project Status

**Active fork.** Production-ready for Streamable HTTP servers on JVM and Babashka.
Breaking changes are possible but minimized — the core protocol handling is stable.

The fork adds ~4000 lines over upstream (transport, registry, promise shim, tests,
Nix infrastructure) while keeping the core (~600 lines) identical to upstream for
easy merging.

## License

EPL v2.0. Copyright (c) [Metosin](https://metosin.fi) and contributors (upstream),
plus [noblepayne](https://github.com/noblepayne) (fork additions).

## Related

- [MCP Clojure SDK](https://github.com/unravel-team/mcp-clojure-sdk) — STDIO-only, JVM-only
- [Clojure MCP](https://github.com/bhauman/clojure-mcp) — another approach
- [Calva Backseat Driver](https://github.com/BetterThanTomorrow/calva-backseat-driver)
