# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(Active dev cycle. Run `bb release-check` before tagging the next release.)

## [0.7.2] — 2026-09-23 — Pure generator step

### Changed

- **The generator step is a pure function.** `next-state` now takes the
  clock reading and 14 random bytes as arguments. The shell (`uuidv7`,
  and the function `make-generator` returns) reads the clock and draws the
  bytes *before* `swap!`. The `swap!` update function therefore has no side
  effects: a retry under contention cannot draw randomness or read the
  clock again. The output format and distribution are unchanged. Each
  UUID now draws 14 random bytes in one call, where before it drew 10 or 4.
- Docstrings say which functions are impure and what they touch: the
  clock, the CSPRNG, or generator state.

### Added (tests)

- Known-answer tests for the pure core, covering every `next-state`
  branch:
  - a new millisecond;
  - a same-millisecond increment, at both ends of its range, 1 and 2^31;
  - both carries;
  - the 74-bit overflow;
  - a clock rollback.
- A known answer for `state->uuid`, and a monotonicity check across
  states, clock readings and random inputs. Each branch was shown to be
  covered by breaking it and watching the tests fail.

### Internal

- The private validator `check-uuidv7!` is now `check-uuidv7`. Under the
  naming convention, `!` marks writes, not "may throw".
- CI uses the runner's preinstalled Chrome for the Scittle tests, because
  Playwright's browser download stalls on GitHub runners. Every job has a
  time limit.

## [0.7.1] — 2026-09-23 — Secure randomness on ClojureScript, nbb and Scittle

### Fixed

- **The random counter bits were not cryptographically secure on
  ClojureScript, nbb and Scittle.** uuidv7 took its randomness from
  `random-uuid`, on the assumption that it used `crypto.getRandomValues`.
  In fact `cljs.core/random-uuid` is built on `rand-int`, i.e.
  `Math.random`. With `Math.random` pinned to a constant, two independent
  generators produced identical "random" bits. The JVM and bb were not
  affected (`UUID/randomUUID` uses `SecureRandom`). The docs and docstrings
  that claimed otherwise are corrected.

### Added

- **`random-bytes`**: `n` bytes from the platform's secure generator.
  - JVM and bb: `SecureRandom`, returning a `byte[]`.
  - ClojureScript, nbb and Scittle: `crypto.getRandomValues`, returning a
    `Uint8Array`, filled in 65,536-byte chunks.
  - It **fails closed**: it throws (`::no-secure-random`) when no secure
    generator exists, instead of falling back.
  - uuidv7 uses it for all its randomness. It adds no dependencies.
- Tests:
  - `test-no-math-random` pins `Math.random` and asserts two generators
    still differ. It failed against 0.7.0.
  - `test-fails-closed-without-crypto` removes `globalThis.crypto` and
    expects the throw.
  - `test-random-bytes` covers sizes and the chunked fill.

### Changed

- CI runs on Node 26 (from 22).
- `bb lint` and `bb fmt` cover every Clojure file in the repo
  (`bin/uuidv7`, `build.clj`, `bb.edn`, `deps.edn` as well as `src` and
  `test`). All were already clean.

## [0.7.0] — 2026-09-22 — Strict validation, CI on every platform

### Changed (breaking for code that catches `AssertionError`)

- **Extraction functions throw `ex-info`** with `{:type
  :com.github.franks42.uuidv7.core/not-uuidv7 :value v}` instead of an
  `AssertionError`. An assert can be compiled out (`*assert*` false,
  `:elide-asserts`), which silently removed the check, and
  `AssertionError` is not caught by `(catch Exception ...)`.

### Fixed

- **`uuidv7?` checks the whole canonical 8-4-4-4-12 form**, in either
  case. It used to look only at characters 14 and 19, so:
  - uppercase UUIDv7 strings were rejected when the variant digit was
    `A` or `B` (and accepted when it was `8` or `9`);
  - strings such as `"xxxxxxxxxxxxxx7xxxx8"` were accepted;
  - `nil` and short strings threw `StringIndexOutOfBoundsException`
    instead of returning false.
- **CLI `parse` and `valid` require the canonical form.**
  `java.util.UUID/fromString` accepts short groups, so
  `uuidv7 valid 1-1-7000-8000-1` exited 0. Uppercase input is accepted
  and output is lowercase, as before.
- **Test runners exit non-zero on failure.** The compiled-CLJS runner
  always exited 0 (`cljs.test/run-tests` returns no summary), as did
  the two JAR runners.
- **README** was still at 0.5.0, and its nbb snippet did not resolve:
  sha `c551762` is not the `v0.5.0` commit. Now at 0.6.0 (`d6afac6`).

### Added

- README section for the `uuidv7` CLI, and a Development section.
- `.github/workflows/ci.yml`: every push and PR runs the library on the
  JVM, bb, nbb, compiled CLJS and Scittle, plus CLI tests, lint and fmt.
  `release.yml` also gates on the JVM tests now.
- bb tasks `test:jvm`, `test:nbb`, `test:cljs`, `test:scittle`
  (headless Chromium via Playwright, `test/runners/run-scittle.mjs`);
  `test:all` runs every platform. `bb lint` also covers `bin/uuidv7`.
- Published-artifact checks: `bb test:scittle-cdn [ref]` loads the
  library from jsdelivr, `bb test:nbb-git` resolves the README's nbb
  coordinates with an empty gitlibs cache, `bb test:published` runs both
  against what the README pins, weekly via `.github/workflows/published.yml`.
  They run `test/published/published_smoke.cljs`, which uses only the
  0.6.0 API so it works against any release.

### Dependencies

- Test/dev runtimes updated to the latest releases: Clojure 1.12.6
  (from 1.12.4), ClojureScript 1.12.145 (from 1.11.132), Scittle 0.8.33
  (from 0.7.30, also in the README snippet). The library JAR still has
  no runtime dependencies.
- Removed the unused `:test-bb` alias, which pinned a Maven coordinate
  (`org.babashka/babashka`) that does not exist.

## [0.6.0] — 2026-05-04 — `uuidv7` CLI shipped

The library now ships with a command-line filter for generating, parsing, and validating UUIDv7s. Library API gains a single `version` constant; otherwise no breaking changes from 0.5.0.

### Added

- **`bin/uuidv7`** — single executable bb script with three subcommands:
  - **`gen`** — generate one UUIDv7. Output formats: `--format uuid` (default; bare UUID string), `--format urn` (`urn:uuid:...`), or `--format edn` (full record `{:uuid :uri :datetime :counter}`). Optional `--output <file>` for direct file writes.
  - **`parse`** — parse one or more UUIDv7s into structured form. Input from positional arg, `--input <file>` (one per line), or stdin (one per line). Same three output formats. Mutually-exclusive checks on input sources.
  - **`valid`** — predicate. Exit 0 if all inputs are UUIDv7, 1 on first malformed/non-v7. No stdout. Same input modes as `parse`.
- **Source resolution** mirrors the cedn CLI pattern: dev mode (running from inside the repo) uses local `src/` via `babashka.classpath/add-classpath`; release mode (the GitHub Release artifact, no adjacent `src/`) uses `babashka.deps/add-deps` to resolve the pinned uuidv7 version from Clojars on first run.
- **Library**: new public `version` constant in `com.github.franks42.uuidv7.core`. Lets the CLI import the version directly; useful for any consumer that wants to log "I'm using uuidv7 X.Y.Z".
- **`bb.edn`** with tasks: `test:bb`, `test:cli`, `install`, `test:cli-release` (installs JAR to local m2 + runs CLI from a clean dir to verify add-deps), `release-check`, `lint`, `fmt`, `fmt:fix`, `check`, `test:all`.
- **`.github/workflows/release.yml`** — fires on `v*.*.*` tag push. Validates tag matches the version constants in `bin/uuidv7`, `build.clj`, AND `core.cljc`; prefetches deps; runs library + CLI tests + lint + fmt + release-check; deploys library to Clojars; waits for Clojars indexing; smoke-tests CLI in a clean directory (forcing `add-deps` resolution from Clojars); creates the GitHub Release with the version-suffixed asset.
- **24 CLI integration tests** in `test/uuidv7/cli_test.clj` covering all three subcommands, all output formats, all input modes, error paths, and round-trip extraction parity with the library API.

### Distribution

| Artifact | Where | Coord / asset |
| --- | --- | --- |
| Library JAR | Clojars | `com.github.franks42/uuidv7 {:mvn/version "0.6.0"}` |
| CLI script | GitHub Release on this repo | `uuidv7-v0.6.0` |

### Install (CLI)

```bash
curl -L https://github.com/franks42/uuidv7.cljc/releases/download/v0.6.0/uuidv7-v0.6.0 -o uuidv7
chmod +x uuidv7
./uuidv7 --version
# uuidv7 0.6.0
```

Requires [babashka](https://babashka.org/) (`bb` on PATH). On first run from a clean install, the script resolves uuidv7 from Clojars (~500 ms one-time cost; cached in `~/.m2` thereafter).

### Composition with other tools

```bash
# Generate UUIDs and feed downstream pipelines
uuidv7 gen | sha256sum

# Parse a UUIDv7 into Nushell typed values
nu -c 'plugin use edn; ^uuidv7 parse "0195a4c8-..." | from edn'

# Validate a list before processing
cat ids.txt | uuidv7 valid && do-something-with ids.txt

# Generate canonical-EDN-shaped records (composable with cedn)
uuidv7 gen --format edn | cedn | sha256sum
```

## [0.5.0] and earlier

Library-only releases. UUIDv7 generation per RFC 9562 Method 3 across JVM, Babashka, ClojureScript (compiled), nbb (Node.js), and Scittle (browser). See git tags for details.
