# Project Review: uuidv7.cljc

**Reviewer:** inkling-2-mini
**Date:** November 21, 2026
**Scope:** Full review of library, CLI, tests, build/CI, and docs at v0.7.3 (current)
**Method:** Read-only review — source, tests, build, CI, and docs. Tests were not run; counts cited are as documented in CLAUDE.md and `docs/uuidv7-tests.md`.

---

## Overall

This is a small, carefully engineered library. The pure/impure split (`next-state` + `advance!` shell) is exactly right for `swap!` correctness, the CSPRNG story is solid on all five targets (fail-closed since 0.7.1), and the verification apparatus — five-platform test matrix, known-answer tests for every branch of the generator step, published-artifact smoke tests, three-way version cross-check before deploy — is unusually thorough for a single-file library. The core algorithm checks out against RFC 9562: bit layout, seed/increment uniformity, carry propagation, overflow handling, and clock-rollback handling are all as documented.

No bugs found in this pass. The actionable items below are observations, not defects.

## What the project does

- **Library**: portable UUIDv7 (RFC 9562 §6.2 Method 3) generator — 48-bit millisecond timestamp + 74-bit monotonic random counter, strictly increasing per generator instance. Zero runtime dependencies, one source file, `.cljc` reader conditionals for JVM / Babashka / ClojureScript / nbb / Scittle.
- **CLI** (`bin/uuidv7`): `gen` / `parse` / `valid` subcommands for shell pipelines — generate, parse into structured EDN, or predicate-check UUIDs. Dev/release source resolution: local `src/` in-repo, Clojars `add-deps` in release.
- **Packaging**: JAR to Clojars (`com.github.franks42/uuidv7`, v0.7.3); CLI as a GitHub Release asset; release workflow deploys on `v*.*.*` tags.
- **Consumers**: used by signet 0.10.0 (per CLAUDE.md).

## How the code works

### The generator step

- The 74-bit counter is split into three fields — 12-bit `rand-a` + 30-bit `rand-b-hi` + 32-bit `rand-b-lo` — so each stays within JS safe-integer range; arithmetic uses `quot`/`rem` (never bit-shifts) so it is correct on both 64-bit JVM longs and 53-bit-safe JS doubles.
- On a **new millisecond**: reseed from 10 random bytes (12 + 30 + 32 bits, each `mod` a power of two — uniform).
- On a **same/earlier millisecond** (clock rollback included): increment by a random amount in `[1, 2^31]` (4 bytes) — random steps keep the counter unpredictable and reduce inter-generator collision risk.
- Carry propagation: `rand-b-lo` → `rand-b-hi` → `rand-a`. On the 74-bit overflow (`new-a >= 4096`): advance `ts` by 1 and reseed — safe because the new timestamp is strictly greater.
- **`next-state` is pure** (state, clock reading, 14 random bytes → new state) so it can run inside `swap!` (which may retry) without side effects, and every branch has a known-answer test. **`advance!` is the shell**: it reads the clock and draws the 14 bytes *before* `swap!`, then applies the pure function.

### Randomness

- `random-bytes`: `java.security.SecureRandom` on JVM/bb; `crypto.getRandomValues` (chunked at 65,536 bytes) on CLJS/nbb/Scittle. Throws `::no-secure-random` when no secure generator exists — fails closed, never falls back to `Math.random` (the pre-0.7.1 bug). `test-no-math-random` pins `Math.random` to a constant to catch regressions.

### Construction, validation, extraction

- **JVM path**: two longs (`msb`, `lsb`) into the `java.util.UUID` constructor — no string allocation. **CLJS path**: hex string built via `to-hex`/`parse-uuid` (`uuid` constructor is not mapped in Scittle). Both print as `#uuid "..."`.
- `uuidv7?`: strict regex — canonical 8-4-4-4-12 form, version 7, variant 10xx, either case. Returns false, never throws.
- Extractors (`extract-ts`, `extract-counter`, `extract-key`, `extract-inst`) validate first and throw `ex-info {:type ::not-uuidv7}` otherwise; `extract-key` is the sortable `[ts a bh bl]` composite key, lexicographically ordered exactly like the UUID. UUID strings themselves are already sortable keys.

### CLI

- `gen` (`--format uuid|urn|edn`, `--output`), `parse` (positional args / `--input <file>` / stdin, `--format`, `--output`), `valid` (same input modes, exit 0/1). `gen` refuses positional args (exit 2); `parse`/`valid` take every positional as an input (0.7.3 fix).
- Raw UTF-8 output via OutputStream, EPIPE-clean (catches `IOException`), exit codes 0/1/2, every positional checked.
- Source resolution: if `../src/com/github/franks42/uuidv7/core.cljc` exists relative to the script → local classpath; otherwise `babashka.deps/add-deps` from Clojars (cached in `~/.m2`).

### Build, test, CI

- `build.clj` (tools.build + deps-deploy): zero-runtime-dependency JAR, deploys to Clojars. `deps.edn`: per-target test aliases. `bb.edn`: `test:all` = JVM + bb + nbb + compiled CLJS + headless Scittle (Playwright/Chromium) + CLI + lint + fmt.
- Documented results (CLAUDE.md): 19 tests / 252 assertions JVM+bb; 20 / 242 CLJS/nbb/Scittle; 31 CLI tests.
- CI: `ci.yml` (push/PR) runs the full matrix; `published.yml` (weekly) smoke-tests the README's jsdelivr CDN tag and nbb git dep against *released* artifacts; `release.yml` (`v*.*.*` tag) validates the three version constants agree, runs lib + CLI tests + lint/fmt + `release-check` (refuses non-semver), deploys, waits for Clojars indexing, smoke-tests the CLI in a clean dir, creates the GitHub Release.
- Tests: `test/uuidv7/core_test.cljc` (shared suite, incl. JVM concurrency test and pure-step known-answer tests), `test/uuidv7/cli_test.clj` (shells out to `bin/uuidv7`), `test/published/published_smoke.cljs` (release smoke test, only uses API present since 0.6.0), `test/runners/` (per-platform runners).

## Findings

### Concurrency receipt-order caveat (documented, worth remembering)

Strict increase holds in `swap!` commit order; two threads may *receive* their UUIDs in the opposite order. The docstring and README say this; callers who fan out across threads must sort by value, not by arrival. No action needed.

### `advance!` draws 14 bytes per call (documented tradeoff)

Each UUID draws 14 bytes (10 for seed, 4 for increment) even though a given step uses only 10 or 4. Deliberate — keeps the pure step's argument shape uniform — and documented in CLAUDE.md. Noting the entropy/allocation cost per call for anyone benchmarking.

### Minor observations (no action needed)

- `extract-key` triple-validates (`check-uuidv7` runs in `extract-key`, `extract-ts`, `extract-counter`) — negligible.
- `read-uuid-strings` slurps stdin whole — fine for a filter, worth knowing for very large inputs.
- `uuidv7 parse`/`valid` with no args block on stdin when attached to a TTY — standard filter behavior.
- CLI `die`/`die-usage`/`run-gen`/`run-parse`/`run-valid` terminate the process; `make-output-stream` truncates a file. Left as-is in 0.7.3 (not writes of lasting state under the project's naming convention).

### Historical reviews

The three pre-0.7.0 reviews (`devin-swe2`, `gemini3pro`, `gpt-5.1-codex-max`, `qwen3-coder-next`) are labelled historical in their headers; `devin-swe2` is labelled the current review but predates this one. Their actionable items were resolved in 0.7.0–0.7.3.

## Bottom line

The core is correct and the engineering is disciplined: pure generator step with known-answer tests, fail-closed CSPRNG, strict validation, five-platform matrix, and a release process that cross-checks versions and smoke-tests released artifacts. No bugs found in this pass. The remaining work is all optional/polish, not correctness.

---

## Open questions

- Should this review supersede `devin-swe2.md` as the "current review" (its header still says it is)? I did not edit that file — relabeling is your call.
- Any area you want me to dig into further (e.g. known-answer test coverage, CLI arg parsing, the release workflow)?

---

## Decisions

- 2026-11-21 — Reviewed read-only, at v0.7.3; no files changed except this review. (The review documents state, not fixes.)
