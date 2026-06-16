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
| Request timeouts | ✅ Done |
| Graceful shutdown | ✅ Done |
| Integration tests (real HTTP lifecycle) | ✅ Done |
| Session TTL sweeper | ✅ Done |
| Transport error body consistency | ✅ Done |
| Content-Type enforcement | ✅ Done |
| notifications/initialized state machine | ✅ Done |
| Flake.nix (Nix distribution) | ✅ Done |
| deps-lock.json (clj-nix lockfile) | ✅ Done |
| SSE heartbeats | 🔵 Deferred (user doesn't use SSE) |
| Registry | 🟢 Keep, stop enhancing |
| Name munging verified | 🟢 Works on bb, verified |

---

## Current Sprint: Nix Distribution & Production Readiness

### DONE

- [x] **Name munging** — URL encoding instead of `__` separator in `impl/common.cljc`
- [x] **Structured logging hook** — `clojure.tools.logging` facade (works on bb), log at request boundaries
- [x] **Request timeouts** — configurable timeout on tool dispatch, return JSON-RPC error on timeout
- [x] **Integration tests** — real http-kit server, real HTTP requests, full round-trip
- [x] **Graceful shutdown** — SIGTERM handler: stop accepting, drain in-flight, close SSE, stop server
- [x] **Session TTL sweeper** — wire up `prune-expired-sessions!` to background timer
- [x] **Transport error consistency** — align all transport errors to JSON-RPC format
- [x] **Content-Type enforcement** — require `application/json` on POST /mcp
- [x] **`notifications/initialized` state machine** — refuse methods before handshake complete
- [x] **Verify promise shim `allOf`** — check result collection matches promesa `p/all`
- [x] **Flake.nix** — devShell + deps-lock package for Nix distribution
- [x] **deps-lock.json** — 4281-entry clj-nix lockfile for reproducible builds

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

### 2026-06-15 — Production Readiness Sprint

- **Request timeouts** — `:timeout` field on tool registration → `p/timeout` in tool-call-handler
- **Graceful shutdown** — `addShutdownHook` in `run-server`, `shutdown-timeout` option, orderly drain
- **Session TTL sweeper** — background daemon thread via `start-session-sweeper!`, configurable interval/ttl
- **Transport error consistency** — all transport errors now `{:jsonrpc "2.0" :error {:code ... :message ...}}`
- **Content-Type enforcement** — POST `/mcp` rejects non-`application/json` with 415
- **Integration tests** — 7 real http-kit server lifecycle tests (full lifecycle, terminated rejection, content-type, bad version, missing body, invalid session, health)
- **Lint/format** — 0 errors, 0 warnings; cljfmt applied
- **CLJS verified** — promise shim + core protocol files are `.cljc`; only transport is `.clj`
- **mcp-clojure-sdk spec review** — confirmed: no gaps, our implementation correct on cancellation/progress/version
- **87 JVM + 8 bb = 95 tests passing**

### 2026-06-15 — Nix Distribution

- **flake.nix** — devShell (jdk21, clojure, babashka, clj-kondo, cljfmt, jet) + `deps-lock` package
- **deps-lock.json** — generated via `nix run github:jlesquembre/clj-nix#deps-lock`, 4281 entries
- **flake.lock** — pinned nixpkgs, clj-nix, flake-utils
- `nix flake check` validates: formatter, packages.deps-lock, devShells.default all green
- Tagged: `v0.2.2`

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
