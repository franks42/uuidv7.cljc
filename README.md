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
com.github.franks42/uuidv7 {:mvn/version "0.4.1"}
```

### Babashka (bb.edn)

```clojure
{:deps {com.github.franks42/uuidv7 {:mvn/version "0.4.1"}}}
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

;; Extract the counter as a 19-char hex string (string-comparable)
(uuidv7/extract-counter-hex u)

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
| `(extract-ts uuid)` | Extract Unix epoch timestamp (ms) from a UUIDv7 |
| `(extract-inst uuid)` | Extract creation timestamp as a Date/inst |
| `(extract-counter uuid)` | Extract the 74-bit counter as `[rand-a rand-b-hi rand-b-lo]` |
| `(extract-counter-hex uuid)` | Extract the 74-bit counter as a 19-char hex string |
| `(extract-key uuid)` | Extract sortable composite key `[ts rand-a rand-b-hi rand-b-lo]` |

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
<script src="https://cdn.jsdelivr.net/npm/scittle@0.7.30/dist/scittle.js"
        type="application/javascript"></script>

<!-- Load the library -->
<script type="application/x-scittle"
        src="https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.4.1/src/com/github/franks42/uuidv7/core.cljc"></script>

<!-- Use it -->
<script type="application/x-scittle">
(require '[com.github.franks42.uuidv7.core :as uuidv7])
(println (uuidv7/uuidv7))
</script>
```

Alternatively, fetch the source via JavaScript and evaluate it explicitly:

```javascript
var src = await fetch("https://cdn.jsdelivr.net/gh/franks42/uuidv7.cljc@v0.4.1/src/com/github/franks42/uuidv7/core.cljc").then(r => r.text());
scittle.core.eval_string(src);
scittle.core.eval_string("(require '[com.github.franks42.uuidv7.core :as uuidv7])");
scittle.core.eval_string("(println (uuidv7/uuidv7))");
```

**Note:** scittle does not expose the `uuid` constructor function (`(uuid "...")`) — use `parse-uuid` instead, which works on all targets and validates the input format.

## License

Copyright (c) Frank Siebenlist. Distributed under the [Eclipse Public License v2.0](LICENSE).
