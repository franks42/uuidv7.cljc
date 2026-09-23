# uuidv7.cljc

Portable UUIDv7 ([RFC 9562](https://www.rfc-editor.org/rfc/rfc9562)) generator for Clojure, ClojureScript, Babashka, nbb, and scittle.

Implements Method 3 (monotonic random counter) with:
- 48-bit millisecond Unix timestamp
- 74-bit monotonically increasing random counter
- Sub-millisecond ordering guaranteed from a single generator
- No blocking, no spinning, no overflow in practice

## Installation

### deps.edn

```clojure
com.github.franks42/uuidv7 {:mvn/version "0.7.1"}
```

### Babashka (bb.edn)

```clojure
{:deps {com.github.franks42/uuidv7 {:mvn/version "0.7.1"}}}
```

### nbb (nbb.edn)

nbb cannot read JAR files, so use a git dependency instead:

```clojure
{:deps {com.github.franks42/uuidv7
        {:git/url "https://github.com/franks42/uuidv7.cljc"
         :git/tag "v0.7.0"
         :git/sha "8b8d9d7"}}}
```

## Usage

```clojure
(require '[com.github.franks42.uuidv7.core :as uuidv7])

;; Generate a UUIDv7
(uuidv7/uuidv7)
;=> #uuid "0195xxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx"

;; Successive calls are guaranteed strictly increasing
(repeatedly 5 uuidv7/uuidv7)

;; Extract the embedded timestamp (ms since epoch)
(uuidv7/extract-ts u)
;=> 1738934578991

;; Extract as a Date/inst
(uuidv7/extract-inst u)
;=> #inst "2025-02-07T..."

;; Extract sortable composite key [ts rand-a rand-b-hi rand-b-lo]
(uuidv7/extract-key u)

;; Extract the 74-bit counter as [rand-a rand-b-hi rand-b-lo]
(uuidv7/extract-counter u)

;; UUIDv7 strings sort in generation order — use (str u) as a sortable key
(sort (map str (repeatedly 5 uuidv7/uuidv7)))

;; Validate UUID version before extraction
(uuidv7/uuidv7? u)  ;=> true if version 7

;; Extraction functions require a UUIDv7. Use uuidv7? to check first:
(when (uuidv7/uuidv7? u)
  (uuidv7/extract-ts u))  ;=> Safe to call after validation

;; Independent generator with its own monotonic state (e.g. per-thread)
(def gen (uuidv7/make-generator))
(gen)
;=> #uuid "0195xxxx-..."
```

## API

| Function | Description |
|---|---|
| `(uuidv7)` | Generate a UUIDv7 with monotonic sub-millisecond ordering |
| `(make-generator)` | Create an independent generator with its own monotonic state |
| `(uuidv7? uuid)` | True for a v7 UUID object or canonical 8-4-4-4-12 string (either case); false for anything else, never throws |
| `(extract-ts uuid)` | Extract Unix epoch timestamp (ms) from a UUIDv7 (throws `ex-info` if not v7) |
| `(extract-inst uuid)` | Extract creation timestamp as a Date/inst (throws `ex-info` if not v7) |
| `(extract-counter uuid)` | Extract the 74-bit counter as `[rand-a rand-b-hi rand-b-lo]` (throws `ex-info` if not v7) |
| `(extract-key uuid)` | Extract sortable composite key `[ts rand-a rand-b-hi rand-b-lo]` (throws `ex-info` if not v7) |
| `(random-bytes n)` | `n` bytes from the platform's secure generator (`byte[]` from `SecureRandom` on JVM/bb, `Uint8Array` from `crypto.getRandomValues` on CLJS/nbb/Scittle). Throws if none is available |

All randomness comes from the platform's cryptographically secure
generator, and there are no dependencies. Before 0.7.1, ClojureScript,
nbb and Scittle used `cljs.core/random-uuid`, which is built on
`Math.random`. Upgrade if you use uuidv7 on those platforms.

## Command line

`uuidv7` is a Babashka script that generates, parses and validates
UUIDv7s. Download it from the
[latest release](https://github.com/franks42/uuidv7.cljc/releases/latest):

```bash
curl -fsSL -o uuidv7 https://github.com/franks42/uuidv7.cljc/releases/download/v0.7.1/uuidv7-v0.7.1
chmod +x uuidv7
```

On first run it fetches the matching library version from Clojars
(cached in `~/.m2` afterwards).

```bash
uuidv7 gen                        # 0195a4c8-...-7...  (bare UUID)
uuidv7 gen --format urn           # urn:uuid:0195a4c8-...
uuidv7 gen --format edn           # {:uuid #uuid "..." :uri "urn:uuid:..." :datetime #inst "..." :counter [a bh bl]}
uuidv7 parse 0195a4c8-...         # the same EDN record for an existing UUIDv7
uuidv7 valid "$id" && echo ok     # exit 0 if every input is a UUIDv7, else 1
uuidv7 gen --format edn | cedn | sha256sum   # canonical bytes via the cedn CLI
```

`parse` and `valid` take a positional UUID, `--input <file>`, or stdin
(one per line). `gen` and `parse` accept `--output <file>`. Exit codes:
`0` success, `1` malformed UUID, non-v7 UUID or I/O error, `2` usage
error. A closed downstream pipe (`uuidv7 parse ... | head`) is not an
error. Run `uuidv7 <subcommand> --help` for details.

## Platform Support

| Platform | UUID type | Tested |
|---|---|---|
| Clojure (JVM) | `java.util.UUID` | Yes |
| ClojureScript (compiled) | `cljs.core/UUID` | Yes |
| Babashka | `java.util.UUID` | Yes |
| nbb | `cljs.core/UUID` | Yes |
| scittle | `cljs.core/UUID` | Yes |

## Scittle (Browser) Usage

To use uuidv7 in a browser page with [scittle](https://github.com/babashka/scittle), load the `.cljc` source file via a `<script>` tag. Scittle v0.6.17+ handles `#?` reader conditionals in `.cljc` files correctly.

```html
<script src="https://cdn.jsdelivr.net/npm/scittle@0.8.33/dist/scittle.js"
        type="application/javascript"></script>

<!-- Load the library -->
<script type="application/x-scittle"
        src="https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.7.1/src/com/github/franks42/uuidv7/core.cljc"></script>

<!-- Use it -->
<script type="application/x-scittle">
(require '[com.github.franks42.uuidv7.core :as uuidv7])
(println (uuidv7/uuidv7))
</script>
```

Alternatively, fetch the source via JavaScript and evaluate it explicitly:

```javascript
var src = await fetch("https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.7.1/src/com/github/franks42/uuidv7/core.cljc").then(r => r.text());
scittle.core.eval_string(src);
scittle.core.eval_string("(require '[com.github.franks42.uuidv7.core :as uuidv7])");
scittle.core.eval_string("(println (uuidv7/uuidv7))");
```

**Note:** scittle does not expose the `uuid` constructor function (`(uuid "...")`) — use `parse-uuid` instead, which works on all targets and validates the input format.

## Development

```bash
bb test:all       # library on JVM, bb, nbb, compiled CLJS and Scittle, plus CLI, lint, fmt
bb test:jvm       # or: test:bb, test:nbb, test:cljs, test:scittle, test:cli
bb check          # clj-kondo + cljfmt
```

`test:scittle` and `test:cljs` need Node.js; `test:scittle` also needs
Playwright's Chromium (`npm install && npx playwright install chromium`).
CI runs all of these on every push and pull request.

## License

Copyright (c) Frank Siebenlist. Distributed under the [Eclipse Public License v2.0](LICENSE).
