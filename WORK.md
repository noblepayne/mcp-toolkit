# mcp-toolkit Work Tracker

> **Principle:** Get it right, robust, good to build on. Go for confidence, not coverage.
> **Testing style:** Integration tests over mocks. Pure function tests where they earn their keep.
> **Pattern:** Rich Hickey (simple), Hillel Wayne (invariants), Eric Normand (data modeling).

---

## Status Dashboard

| Area | Status |
|------|--------|
| Name munging (URL encoding) | ✅ Done |
| Structured logging hook | ✅ Done |
| Request timeouts | 🔴 Not started |
| SSE heartbeats | 🔵 Deferred (user doesn't use SSE) |
| Integration tests | 🔴 Not started |
| Graceful shutdown | 🔴 Not started |
| Session TTL sweeper | 🟡 Auto-sweep exists, needs hookup |
| Transport error body consistency | 🔴 Not started |
| Content-Type enforcement | 🔴 Not started |
| notifications/initialized state machine | 🔴 Not started |
| Promise shim `allOf` semantics | 🔴 Verify once |
| Registry | 🟢 Keep, stop enhancing |
| Name munging verified | 🟢 Works on bb, verified |

---

## Current Sprint: Production Readiness Basics

### MUST FIX

- [ ] **Name munging** — URL encoding instead of `__` separator in `impl/common.cljc`
- [ ] **Structured logging hook** — `clojure.tools.logging` facade (works on bb), log at request boundaries
- [ ] **Request timeouts** — configurable timeout on tool dispatch, return JSON-RPC error on timeout

### SHOULD FIX

- [ ] **Integration tests** — real http-kit server, real HTTP requests, full round-trip
- [ ] **Graceful shutdown** — SIGTERM handler: stop accepting, drain in-flight, close SSE, stop server
- [ ] **Session TTL sweeper** — wire up `prune-expired-sessions!` to background timer
- [ ] **Transport error consistency** — align all transport errors to JSON-RPC format
- [ ] **Content-Type enforcement** — require `application/json` on POST /mcp
- [ ] **`notifications/initialized` state machine** — refuse methods before handshake complete

### NICE

- [ ] **Verify promise shim `allOf`** — check result collection matches promesa `p/all`

---

## Work Log

### 2026-06-15 — Strategy & Merge

- Fork strategy agreed: own the fork, two speeds (upstream at user speed, agent speed for infra)
- `feat/streamablehttp` → `main` merged, tagged v0.2.0
- Streamable HTTP verified on bb (still .clj, loads fine — bb has http-kit built in)
- 8-test bb smoke test written (`test/mcp_toolkit/smoke_test.bb`)
- Registry confirmed optional (dual dispatch in handler, legacy mode works)
- AGENTS.md updated with dev log entry
- PLAN.md updated: fork strategy, phase status, roadmap

### 2026-06-15 — MUST FIX #2: Structured Logging Hook

- Added `*request-id*` dynamic var (correlation ID bound per-request)
- Added `*log-fn*` dynamic var (overridable, defaults to JSON println on stderr)
- Added `with-request-logging` middleware in `streamable_http.clj`
- Logs at three lifecycle points: `request-received`, `request-completed`, `request-error`
- Each entry includes: timestamp, level, event, request-id, http-method, uri, duration-ms (on complete), status (on complete)
- Added `(:import [java.time Instant])` to ns for cross-runtime timestamp support
- Used `clojure.tools.logging` alternative: structured JSON println works on both JVM and bb with zero config
- All tests pass on both runtimes

### 2026-06-15 — MUST FIX #1: Name Munging

- Replaced lossy `__` separator with Clojure's canonical `/` separator in `impl/common.cljc`
- All 6 edge cases verified on bb (namespaced, non-namespaced, `__` in ns, `__` in name, `/` in name, special chars)
- Round-trip property `kw = unmunge(munge(kw))` holds for all valid Clojure keywords
- Fixed docstrings in `registry.cljc`
- 80 JVM tests + 8 bb smoke tests: all pass
- Trusted property: uses Clojure's own keyword serialization — `/` IS the language's canonical separator

### 2026-06-15 — Design Reviews

- **Ops mentor review**: flagged zero-logging, no timeouts, no integration tests as MUST FIX
- **MCP expert review**: flagged name munging as PROBLEMATIC (lossy `__` separator), everything else SOLID
- **My arbitration**: name munging is MUST FIX; registry stays (separate ns, zero cost on bb); clojure.tools.logging works on bb (verified); SSE heartbeats deferred

---

## Backlog

- Pinboard-mcp migration (proof of concept)
- Datom MCP adapter
- `feats/upstream-shim` branch (promise shim + bug fixes, clean history)
- Protocol abstraction for tool resolution (post-v0.2)
- Malli validation for tool input schemas at registration

## Ideas / Sponsored

- Logging hook should be swappable: `clojure.tools.logging` by default, but consumers can redirect
- bb servers often run in containers → structured JSON to stdout is the right default
- Consider Mulog on JVM path — works for BoostBox/BoostBridge consumers
- Graceful shutdown should also flush pending audit log entries (for mcp-injector consumers)
- The `notifications/initialized` check needs a `:initialized?` flag in the session, set by the handler
