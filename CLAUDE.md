# uuidv7.cljc — Project Guide

Portable UUIDv7 (RFC 9562) library. Single source file, zero runtime dependencies, runs on all major Clojure platforms. **Since v0.6.0**, ships a command-line filter (`bin/uuidv7`) alongside the library.

## Current state

- Latest release: **v0.7.1**
- Library on Clojars: `com.github.franks42/uuidv7 {:mvn/version "0.7.1"}`
- CLI on GitHub Releases: `uuidv7-v0.7.1` asset
- 0.7.0: strict `uuidv7?`, ex-info from the extractors, canonical-form-only CLI input, CI (`ci.yml`), headless Scittle runner, published-artifact checks (`published.yml`). See CHANGELOG.
- Library tests: 15 on JVM/bb (incl. JVM concurrency test), 16 on CLJS/nbb/Scittle. CLI: 27 tests (bb-only). All pass; `bb test:all` runs every platform.
- 0.7.0 verified from outside: full JVM, bb and compiled-CLJS suites against the Clojars JAR (local `~/.m2` copy deleted first), full nbb suite via the README git dep, CDN smoke test, release asset; jsdelivr and JAR sources byte-identical to the tag.
- Runtimes (dev/test): Clojure 1.12.6, ClojureScript 1.12.145, Scittle 0.8.33, bb and nbb `latest` in CI, JDK 21, Node 26.
- 0.7.1: secure randomness on CLJS/nbb/Scittle (`random-bytes`, no dependencies; 0.7.0 and earlier used `Math.random` there), CI on Node 26, `bb lint` / `bb fmt` cover every Clojure file.

## Lint and format

`bb check` (= `bb lint` + `bb fmt`) must be clean before every commit, on
every Clojure file — not just the ones you touched. `bin/uuidv7` has no
extension, so clj-kondo needs `--lang clj`; cljfmt reads it as-is. CI runs
both.

## Project Structure

```
src/com/github/franks42/uuidv7/core.cljc   # the library (single file, includes `version` const)
bin/uuidv7                                   # CLI filter (gen / parse / valid subcommands)
test/uuidv7/core_test.cljc                  # shared library test suite
test/uuidv7/cli_test.clj                    # CLI integration tests (shells out to bin/uuidv7)
test/published/published_smoke.cljs         # smoke test for *released* versions (CDN, nbb git dep)
.clj-kondo/config.edn                       # kondo suppressions
test/runners/                                # per-platform test runners
test/runners/run-scittle.mjs                # headless Scittle runner (Playwright)
test/runners/run-scittle-cdn.mjs            # Scittle smoke test against jsdelivr at a git ref
package.json                                 # Playwright (dev only)
bb.edn                                       # bb tasks (test:bb, test:cli, install, release-check, etc.)
build.clj                                    # tools.build script (jar, install, deploy)
deps.edn                                     # aliases for all test targets + build
.github/workflows/ci.yml                    # push/PR: JVM + bb + CLI + lint/fmt; CLJS + nbb + Scittle
.github/workflows/published.yml             # weekly: CDN (main + README tag), README nbb git dep
.github/workflows/release.yml               # v*.*.* tag → Clojars deploy + GH Release
CHANGELOG.md                                 # Keep-a-Changelog format
```

## Running Tests

```bash
bb test:all        # everything below, plus lint + fmt
bb test:jvm        # test:bb, test:nbb, test:cljs, test:scittle, test:cli
bb test:published  # README's pinned CDN tag + nbb git dep (network)
```

Every task exits non-zero on a failing test (the compiled-CLJS runner
uses a `:end-run-tests` report hook, since `cljs.test/run-tests` returns
no summary). Expected results:
- **CLJ/BB**: `Ran 15 tests containing 100 assertions. 0 failures, 0 errors.` (includes JVM concurrency test)
- **CLJS/nbb/scittle**: `Ran 16 tests containing 90 assertions. 0 failures, 0 errors.` (includes the Math.random and fail-closed tests)

The raw commands the tasks wrap:

### Clojure (JVM)
```bash
clojure -M:test-clj -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
```

### Babashka
```bash
bb --classpath src:test -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
```

### ClojureScript (compiled, Node.js)
```bash
clojure -M:test-cljs                    # compile
node target/cljs-test-out/test-cljs.js  # run
```

### nbb
```bash
nbb -cp src:test -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
```

### nbb (published git dep)
nbb cannot read JAR files, so users get a git dependency. `bb test:nbb-git`
copies the nbb.edn block out of README.md, resolves it with an empty gitlibs
cache and runs `test/published/published_smoke.cljs` against it, checking the
loaded version matches the README's `:git/tag`. (The README's 0.5.0 snippet
was broken — sha `c551762` is not the `v0.5.0` commit — and nothing noticed.)

### Scittle (browser)
```bash
bb test:scittle              # headless Chromium; one-time: npm install && npx playwright install chromium
bb test:scittle-cdn [ref]    # smoke test with the library from jsdelivr at ref (default main)
```
`run-scittle.mjs` serves the repo with `Cache-Control: no-store`, so edits
are always picked up. For a manual look: `python3 -m http.server 8765` and
open `http://localhost:8765/test/runners/test_scittle/index.html`; the
browser *does* cache `<script src>` files there, so cache-bust after edits.

### Clojars-based tests
Test against the published Clojars artifact (excludes local `src` from classpath).
These run the test suite *on main* against a *released* JAR, so they only pass
when main's tests match that release — use them right after a release, or
check out the tag first. Replace `X.Y.Z` with the release:
```bash
# CLJ against Clojars
clojure -Sdeps '{:paths ["test"] :deps {com.github.franks42/uuidv7 {:mvn/version "X.Y.Z"} org.clojure/clojure {:mvn/version "1.12.6"}}}' -M -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"

# BB against Clojars
bb -cp "$(clojure -Sdeps '{:paths [] :deps {com.github.franks42/uuidv7 {:mvn/version "X.Y.Z"}}}' -Spath):test" -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"

# CLJS against Clojars (compile + run)
clojure -Sdeps '{:paths ["test" "test/runners"] :deps {org.clojure/clojure {:mvn/version "1.12.6"} org.clojure/clojurescript {:mvn/version "1.12.145"} com.github.franks42/uuidv7 {:mvn/version "X.Y.Z"}}}' -M -m cljs.main --target node --output-dir target/cljs-clojars-test --output-to target/cljs-clojars-test/test-cljs.js -c test-cljs.core
node target/cljs-clojars-test/test-cljs.js
```

**Note:** If `~/.m2/repository` has a locally-installed copy (from `clojure -T:build install`),
delete it first to ensure you're testing the real Clojars artifact:
```bash
rm -rf ~/.m2/repository/com/github/franks42/uuidv7/X.Y.Z/
```
Verify with: `cat ~/.m2/repository/com/github/franks42/uuidv7/X.Y.Z/_remote.repositories`
— it should show `>clojars=` (not empty after `>=`).

### JAR-based tests (local build)
```bash
clojure -T:build jar      # build JAR first
clojure -M:test-clj-jar   # test CLJ against JAR
clojure -M:test-bb-jar    # test BB against JAR
```

## Building & Deploying

```bash
clojure -T:build jar       # build target/uuidv7.jar
clojure -T:build install   # install to ~/.m2/repository
clojure -T:build deploy    # deploy to Clojars (needs CLOJARS_USERNAME + CLOJARS_PASSWORD)
```

The pom.xml has zero runtime dependencies (`:root nil` in `create-basis`).

### Release workflow

`.github/workflows/release.yml` fires on `v*.*.*` tag push and:

1. Validates the tag matches version constants in **all three** places: `bin/uuidv7`, `build.clj`, `src/com/github/franks42/uuidv7/core.cljc`. Mismatch → fail before any deploy.
2. Runs `clojure -P` (prefetch project deps) so `tools.build/create-basis` can resolve Clojure on a fresh CI runner.
3. Runs lib tests, CLI tests, lint, fmt, `release-check` (refuses non-stable versions like `*-SNAPSHOT`).
4. Deploys library to Clojars.
5. Waits for Clojars indexing.
6. Smoke-tests `bin/uuidv7` in a clean temp dir (forces `add-deps` to resolve from Clojars).
7. Creates a GitHub Release with `uuidv7-vX.Y.Z` as the asset.

To ship a new release:

```bash
# Bump versions in three places: src/.../core.cljc, build.clj, bin/uuidv7
# (keep the version def single-line — workflow's grep extractor relies on it)
# README: Maven coords, nbb :git/tag, jsdelivr @vX.Y.Z URLs, CLI download URL.
# CHANGELOG: turn [Unreleased] into the new version section.
bb release-check        # local refuse-SNAPSHOT check
bb test:all
git commit -am "vX.Y.Z: ..."
git push origin main    # wait for ci.yml to go green
git tag -a vX.Y.Z -m "vX.Y.Z — ..."
git push origin vX.Y.Z  # release.yml fires
```

Afterwards:
1. Point the README's nbb `:git/sha` at the tagged commit in a follow-up
   commit (the tagged commit cannot contain its own sha):
   `git rev-parse --short vX.Y.Z`.
2. `bb test:published` — the CDN bundle at the pinned tag reports X.Y.Z and
   the README's nbb git dep resolves from an empty cache.

The Clojars secrets must be set on the repo (`gh secret set CLOJARS_USERNAME` / `CLOJARS_PASSWORD`). Use a deploy token, not your account password — and use `printf '%s' '<value>'` (or `echo -n`) when piping to avoid trailing newlines that break auth.

## CLI: `bin/uuidv7`

Single executable bb script with three subcommands:

```bash
uuidv7 gen [--format uuid|urn|edn] [--output <file>]
uuidv7 parse [<uuid>] [-i|--input <file>] [--format uuid|urn|edn] [--output <file>]
uuidv7 valid [<uuid>] [-i|--input <file>]
```

- `gen` — generate one UUIDv7. Default format: bare UUID string. `--format urn` produces `urn:uuid:...`. `--format edn` produces `{:uuid #uuid "..." :uri "urn:uuid:..." :datetime #inst "..." :counter [a bh bl]}`.
- `parse` — parse one or more UUIDv7s. Input from positional arg, `--input <file>`, or stdin. Default output is EDN; `--format uuid|urn` for raw forms. Mutex check on positional + `--input`.
- `valid` — predicate: exit 0 if all inputs are UUIDv7, 1 on first malformed/non-v7. No stdout. Same input modes as `parse`.

EPIPE-clean. Errors → stderr + exit 1. Bad usage → exit 2.

### Source resolution (dev vs release)

The script detects whether `../src/com/github/franks42/uuidv7/core.cljc` exists relative to itself:

- **Dev mode** (in-repo): adds `../src/` to classpath via `babashka.classpath/add-classpath`. Uses local source — picks up unpublished changes.
- **Release mode** (downloaded GH Release artifact, no adjacent `src/`): pulls the pinned Maven coord via `babashka.deps/add-deps`. First run resolves from Clojars (~500ms), cached in `~/.m2`.

Same script for both. The `version` constant in `bin/uuidv7` matches the published library version. CI verifies all three version constants agree before deploying.

### Composing with the broader Clojure-shaped Nushell ecosystem

```bash
uuidv7 gen | sha256sum                          # hash a fresh UUID
uuidv7 parse 0195a4c8-... | from edn | get datetime  # via nu_plugin_edn
uuidv7 gen --format edn | cedn | sha256sum      # canonical bytes via cedn CLI
```

## Key Design Decisions

- **Reader conditionals**: Two main branches — `:clj` (JVM + BB) and `:cljs` (CLJS + nbb + scittle). A third `:scittle` branch at end of core.cljc resets namespace.
- **`:scittle` feature flag**: `#?(:scittle (in-ns 'user))` resets namespace so callers can use bare `(require ...)`. Invisible to all other platforms.
- **Three-field counter split**: 12 + 30 + 32 bits keeps each value within JS safe-integer range
- **Functional core, imperative shell.** `next-state` (state, now, 14
  random bytes → state) and `state->uuid` are pure and have known-answer
  tests. `advance!` reads the clock and draws the bytes *before* `swap!`;
  never put a side effect inside a `swap!` update function (it can retry).
  Impure functions say so first in their docstring.
- **Platform CSPRNG, called directly** (`random-bytes`): `SecureRandom` on JVM/bb, `crypto.getRandomValues` on CLJS/nbb/Scittle (65,536-byte chunks, throws when absent). NOT `random-uuid`: `cljs.core/random-uuid` is `Math.random`, which uuidv7 used until 0.7.1. `test-no-math-random` pins `Math.random` to catch a regression. No libsodium dependency, by design: uuidv7 stays dependency-free.
- **`parse-uuid` over `uuid`**: `uuid` constructor exists in ClojureScript but is not mapped to the `uuid` var in scittle; `parse-uuid` works everywhere
- **UUIDv7 strings are sortable keys**: `(str uuid)` preserves generation order under string comparison — no extraction needed for sorting
