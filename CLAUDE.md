# uuidv7.cljc — Project Guide

Portable UUIDv7 (RFC 9562) library. Single source file, zero runtime dependencies, runs on all major Clojure platforms. **As of v0.6.0**, ships a command-line filter (`bin/uuidv7`) alongside the library.

## Current state

- Latest release: **v0.6.0**
- Library on Clojars: `com.github.franks42/uuidv7 {:mvn/version "0.6.0"}`
- CLI on GitHub Releases: `uuidv7-v0.6.0` asset
- 10 library tests + 24 CLI tests = 34 total, all passing across JVM, Babashka, nbb, shadow-cljs, Scittle (lib only — CLI is bb-only).

## Project Structure

```
src/com/github/franks42/uuidv7/core.cljc   # the library (single file, includes `version` const)
bin/uuidv7                                   # CLI filter (gen / parse / valid subcommands)
test/uuidv7/core_test.cljc                  # shared library test suite
test/uuidv7/cli_test.clj                    # CLI integration tests (shells out to bin/uuidv7)
.clj-kondo/config.edn                       # kondo suppressions
test/runners/                                # per-platform test runners
bb.edn                                       # bb tasks (test:bb, test:cli, install, release-check, etc.)
build.clj                                    # tools.build script (jar, install, deploy)
deps.edn                                     # aliases for all test targets + build
.github/workflows/release.yml               # v*.*.* tag → Clojars deploy + GH Release
CHANGELOG.md                                 # Keep-a-Changelog format
```

## Running Tests

Expected results:
- **CLJ/BB**: `Ran 10 tests containing 58 assertions. 0 failures, 0 errors.` (includes JVM concurrency test)
- **CLJS/nbb/scittle**: `Ran 9 tests containing 46 assertions. 0 failures, 0 errors.`

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

### nbb (against Clojars via nbb.edn git dep)
nbb cannot read JAR files. To test against the published source, create an `nbb.edn`
with a git dependency and run from that directory:
```clojure
;; nbb.edn
{:deps {com.github.franks42/uuidv7
        {:git/url "https://github.com/franks42/uuidv7.cljc"
         :git/tag "v0.5.0"
         :git/sha "c551762"}}}
```
```bash
nbb -cp test -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
```

### Scittle (browser)
```bash
python3 -m http.server 8765  # from project root
# Open: http://localhost:8765/test/runners/test_scittle/index.html
# Check data-status attribute on #test-output element: "pass" or "fail"
```

**IMPORTANT: Always clear browser cache when testing modified .cljc files in scittle.**
`<script src="file.cljc">` uses the browser's normal cache — edits are NOT picked up
without a cache-bust (query param `?v=2` or clearing via Playwright CDP).

### Clojars-based tests
Test against the published Clojars artifact (excludes local `src` from classpath):
```bash
# CLJ against Clojars
clojure -Sdeps '{:paths ["test"] :deps {com.github.franks42/uuidv7 {:mvn/version "0.5.0"} org.clojure/clojure {:mvn/version "1.12.4"}}}' -M -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"

# BB against Clojars
bb -cp "$(clojure -Sdeps '{:paths [] :deps {com.github.franks42/uuidv7 {:mvn/version "0.5.0"}}}' -Spath):test" -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"

# CLJS against Clojars (compile + run)
clojure -Sdeps '{:paths ["test" "test/runners"] :deps {org.clojure/clojure {:mvn/version "1.12.4"} org.clojure/clojurescript {:mvn/version "1.11.132"} com.github.franks42/uuidv7 {:mvn/version "0.5.0"}}}' -M -m cljs.main --target node --output-dir target/cljs-clojars-test --output-to target/cljs-clojars-test/test-cljs.js -c test-cljs.core
node target/cljs-clojars-test/test-cljs.js
```

**Note:** If `~/.m2/repository` has a locally-installed copy (from `clojure -T:build install`),
delete it first to ensure you're testing the real Clojars artifact:
```bash
rm -rf ~/.m2/repository/com/github/franks42/uuidv7/0.5.0/
```
Verify with: `cat ~/.m2/repository/com/github/franks42/uuidv7/0.5.0/_remote.repositories`
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
bb release-check        # local refuse-SNAPSHOT check
bb test:bb && bb test:cli && bb check
git commit -am "vX.Y.Z: ..."
git tag -a vX.Y.Z -m "vX.Y.Z — ..."
git push origin main
git push origin vX.Y.Z  # workflow fires
```

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
- **`random-uuid` as CSPRNG**: The one crypto-random primitive available on all five platforms
- **`parse-uuid` over `uuid`**: `uuid` constructor exists in ClojureScript but is not mapped to the `uuid` var in scittle; `parse-uuid` works everywhere
- **UUIDv7 strings are sortable keys**: `(str uuid)` preserves generation order under string comparison — no extraction needed for sorting
