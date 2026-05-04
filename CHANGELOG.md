# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

(Active dev cycle. Run `bb release-check` before tagging the next release.)

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
