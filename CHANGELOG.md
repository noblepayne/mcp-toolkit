# Changelog
All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),

Versions prior to v0.1.0 are considered experimental, their API may change.

## [Unreleased]

## [v0.2.3] - 2026-06-16

### Added

- Shared logging namespace `mcp-toolkit.impl.logging` (`.cljc`, JVM/Babashka/CLJS) with `*log-fn*`, `*request-id*`, and `log-event`; transport moved onto it.
- `tool-call-handler` emits `tool-call-started`, `tool-call-completed` (with duration), and `tool-call-error` in both registry and legacy dispatch modes; only argument keys are logged, not values.
- `server/make-initialize-result` helper builds a spec-compliant `InitializeResult` with the correct `:serverInfo` shape.

### Fixed

- `GET /mcp` without a session returns 405 per the Streamable HTTP spec instead of 400; the old status broke OpenCode remote MCP connections.

### Changed

- Transport-local logging defs removed from `streamable_http.clj` in favor of the shared namespace.
- README rewritten around the fork: verified namespace paths, case-based dispatch example, `:inputSchema` shape, Babashka quickstart, registry usage, Nix dev shell.

## [v0.2.2] - 2026-06-15

### Added

- Graceful shutdown via JVM shutdown hook with configurable `shutdown-timeout`.
- Session TTL sweeper: background daemon with configurable interval and TTL.
- Nix distribution: `flake.nix` devShell (JDK 21, Clojure, Babashka, clj-kondo, cljfmt, jet) plus `deps-lock.json` and pinned `flake.lock`.
- Seven real http-kit server-lifecycle integration tests.

### Changed

- All transport errors use JSON-RPC error format.
- `POST /mcp` with a non-`application/json` content type is rejected with 415.

## [v0.2.1] - 2026-06-15

### Added

- Structured request logging in the transport: `*request-id*` per-request correlation, overridable `*log-fn*` (default JSON to stderr), `with-request-logging` middleware emitting `request-received`, `request-completed` (status + duration), `request-error`.

### Fixed

- Name munging separator changed from lossy `__` to Clojure's canonical `/`; the old scheme corrupted keywords whose namespace contained `__`.

## [v0.2.0] - 2026-06-15

### Added

- Streamable HTTP transport (MCP 2025-11-25): `POST /mcp` messaging with session management, `GET /mcp` SSE stream, `DELETE /mcp` session termination, `MCP-Session-Id` handling, configurable origin validation, `/health`.
- Server-initiated SSE notifications: `push-notification!` queues when no channel is open, `drain-pending-messages!` on SSE connect.
- Plugin registry with O(1) tool index, duplicate plugin/tool-name detection, lifecycle hooks, Malli schemas; `create-session` accepts `:registry`.
- Tool-call dispatch routes through the registry when present, with per-tool timeouts; falls back to legacy `:tool-by-name` lookup.
- Cross-runtime promise shim (`mcp-toolkit.impl.promise`): promesa on JVM/CLJS, `CompletableFuture` on Babashka.
- Protocol-boundary name conversion (`munge-name`/`unmunge-name`) between namespaced keywords and wire strings.
- Test infrastructure: test.check/chuck property tests, registry/session/HTTP fixtures, 80 JVM tests plus 8 Babashka smoke tests.

### Fixed

- Transport spec-compliance pass: protocol-version validation, session tombstones with atomic delete, three-way session handling on POST/GET/DELETE, request-vs-notification dispatch helper.
- SSE race fixed: channel registration and pending-queue drain happen in one atomic swap.
- `request-tool-list` checked the `:prompts` capability instead of `:tools`; `resource-read-handler` returned an error map inside `:result` instead of throwing; `route-message` converts handler exceptions into JSON-RPC errors.
- Header lookup is case-insensitive across keyword, string, and lowercase variants.
- Removed a daemon-thread leak (one pruning thread per `create-handler` call); expiry pruning is opt-in via `prune-expired-sessions!`.
- Server and transport negotiate `2024-11-05`, `2025-06-18`, `2025-11-25` (default `2025-11-25`); bb promise `all` uses `.join` to avoid `ExecutionException` wrapping.

## [v0.1.1-alpha] - 2025-07-03

### Fixed

- Server API: completion functions for prompts are now optional.
- Server API: completion functions for resource URIs are now optional.
- Server API: `:resource-templates` is now optional in the session.
- Server API: A promise bug when a response from an MCP client contained an error.
- Server API: The default log level, if unspecified, is now "debug".

### Changed

- `json-rpc/handle-message`'s method signature was changed from `[context]` to `[context message]`.

## [v0.1.0-alpha] - 2025-07-01

### Added

- First release 🎉
