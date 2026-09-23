# UUIDv7 Tests

This document describes the test suite for uuidv7.cljc and how tests are run across all supported platforms.

## Test Coverage

A single shared test file — `test/uuidv7/core_test.cljc` — runs on all platforms: 13 tests / 84 assertions on CLJS, nbb and Scittle, 14 / 96 on the JVM and bb (which add a concurrency test). `test/uuidv7/cli_test.clj` covers `bin/uuidv7` (27 tests, bb only), and `test/published/published_smoke.cljs` smoke-tests released versions.

### 1. UUID Generation (`test-uuidv7-generation`)

- **Valid UUID generation**: Verifies that `uuidv7` produces a valid UUID object
- **Version 7 compliance**: Confirms the version nibble is set to `7` (bits 47-44 of the timestamp)
- **Variant 10xx compliance**: Confirms the variant bits are set correctly (bits 65-66 = `10`)
- **Monotonic ordering**: Successive calls produce strictly increasing UUIDs
- **Sub-millisecond ordering**: Multiple calls within the same millisecond are strictly ordered

### 2. Timestamp Extraction (`test-extract-ts`)

- **Correct timestamp extraction**: `extract-ts` returns a valid Unix epoch timestamp
- **String input support**: `extract-ts` works with both UUID objects and UUID strings
- **Temporal sanity**: Extracted timestamp is within 1 second of current time

### 3. Counter Extraction (`test-extract-counter`)

- **Vector structure**: Returns a three-element vector `[rand-a rand-b-hi rand-b-lo]`
- **Valid ranges**:
  - `rand-a`: 12 bits, range [0, 4095]
  - `rand-b-hi`: 30 bits, range [0, 1,073,741,823]
  - `rand-b-lo`: 32 bits, range [0, 4,294,967,295]

### 4. Composite Key Extraction (`test-extract-key`)

- **Vector structure**: Returns a four-element vector `[ts rand-a rand-b-hi rand-b-lo]`
- **Comparison consistency**: Keys compare in the same order as their source UUIDs

### 5. Date Extraction (`test-extract-inst`)

- **Date type**: Returns a `java.util.Date` (JVM) or `js/Date` (ClojureScript/Babashka)

### 6. Monotonicity (`test-monotonicity`)

- **Independent generators**: `make-generator` creates generators with separate state
- **Generator ordering**: Multiple calls from the same generator are strictly increasing
- **Cross-generator uniqueness**: Different generators produce different UUIDs

### 7. Key Consistency (`test-key-extraction-consistency`)

- **Order preservation**: Key vector comparison matches UUID comparison
- **100% consistency**: 20 random samples all maintain ordering between UUID and counter

### 8. String Format (`test-hex-string-format`)

- **Standard format**: UUID prints as `xxxxxxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx`
- **36 characters**: Total length with 4 hyphens
- **Version at position 14**: Character at index 14 is `7`
- **Variant at position 19**: Character at index 19 is `8`, `9`, `a`, or `b`

### 9. String Sorting (`test-string-sorting-order`)

- 100 UUIDs' string forms sort in generation order

### 10. Validation accepts (`test-uuidv7?-accepts`)

- Generated UUIDs and their strings; uppercase and mixed case for every variant digit

### 11. Validation rejects (`test-uuidv7?-rejects`)

- v4 and wrong-variant UUIDs; strings that are right only at positions 14 and 19; wrong length, no dashes, braces, `urn:uuid:` prefix, leading space
- `nil`, empty, short and non-string values return false and never throw

### 12. Extractors reject non-v7 (`test-extraction-rejects-non-v7`)

- Every extractor throws ex-info with `:type ::not-uuidv7` for a v4 UUID, garbage and `nil`

### 13. Uppercase extraction (`test-extraction-accepts-uppercase`)

- An uppercase string extracts the same key as its lowercase form

### 14. Concurrency (`test-concurrent-generation`, JVM/bb only)

- 10 threads × 1000 UUIDs from the shared generator are all unique
- Per-thread generators each produce strictly monotonic sequences

## Running Tests

`bb test:all` runs every platform plus CLI tests, lint and fmt; `bb test:jvm`,
`test:bb`, `test:nbb`, `test:cljs`, `test:scittle` and `test:cli` run one each
and exit non-zero on failure. The commands they wrap:

### Clojure (JVM)

```bash
clojure -M:test-clj -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
```

### Babashka

```bash
bb --classpath src:test -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
```

### ClojureScript (compiled, Node.js target)

```bash
clojure -M:test-cljs                    # compile
node target/cljs-test-out/test-cljs.js  # run
```

### nbb (Node.js Babashka)

```bash
nbb --classpath src:test -e "(require '[clojure.test :as t] '[uuidv7.core-test]) (t/run-tests 'uuidv7.core-test)"
```

### Scittle (browser)

```bash
node test/runners/run-scittle.mjs   # headless Chromium via Playwright
# or manually: python3 -m http.server 8765, then open
# http://localhost:8765/test/runners/test_scittle/index.html
```

### JAR-based tests (CLJ and BB)

```bash
clojure -T:build jar         # build the JAR first
clojure -M:test-clj-jar      # test with Clojure
bb --classpath target/uuidv7.jar:test:test/runners -e "(require 'test-bb-jar.core) (test-bb-jar.core/-main nil)"
```

## Test Architecture

```
test/
  uuidv7/core_test.cljc          # shared test suite (all platforms)
  runners/
    test_cljs/core.cljs           # compiled CLJS runner (Node.js)
    test_nbb/core.cljs            # nbb runner
    test_clj_jar/core.clj         # CLJ JAR runner
    test_bb_jar/core.clj          # BB JAR runner
    test_scittle/
      index.html                  # browser test page
      clojure_test_shim.cljs      # clojure.test shim for SCI
```

## Key Differences Between Environments

| Aspect | JVM/BB | CLJS/nbb/scittle |
|--------|--------|------------------|
| UUID constructor | `UUID.` / `parse-uuid` | `parse-uuid` |
| Timestamp | `System/currentTimeMillis` | `js/Date.now` |
| Hex conversion | `Long/toHexString` | `.toString(..., 16)` |
| Integer parsing | `Long/parseLong` | `js/parseInt` |

The `.cljc` implementation handles these differences using reader conditionals (`#?(:clj ... :cljs ...)`).

## Build Artifacts

The build script (`build.clj`) creates:

- `target/classes/` - Compiled classes and resources
- `target/uuidv7.jar` - JAR file for deployment
- `target/classes/META-INF/maven/com.github.franks42/uuidv7/pom.xml` - Maven POM
