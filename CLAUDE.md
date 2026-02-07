# uuidv7.cljc — Project Guide

Portable UUIDv7 (RFC 9562) library. Single source file, zero dependencies, runs on all major Clojure platforms.

## Project Structure

```
src/com/github/franks42/uuidv7/core.cljc   # the library (single file)
test/uuidv7/core_test.cljc                  # shared test suite (8 tests, 45 assertions)
test/runners/                               # per-platform test runners
build.clj                                   # tools.build script (jar, install, deploy)
deps.edn                                    # aliases for all test targets + build
```

## Running Tests

All platforms should produce: `Ran 8 tests containing 45 assertions. 0 failures, 0 errors.`

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
nbb --classpath src:test -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
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

### JAR-based tests
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

## Key Design Decisions

- **Reader conditionals**: Only two branches — `:clj` (JVM + BB) and `:cljs` (CLJS + nbb + scittle)
- **`:scittle` feature flag**: `#?(:scittle (in-ns 'user))` at end of core.cljc resets namespace so callers can use bare `(require ...)`. Invisible to all other platforms.
- **Three-field counter split**: 12 + 30 + 32 bits keeps each value within JS safe-integer range
- **`random-uuid` as CSPRNG**: The one crypto-random primitive available on all five platforms
- **`parse-uuid` over `uuid`**: `uuid` constructor is not available in scittle; `parse-uuid` works everywhere
