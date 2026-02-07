# uuidv7.core — Architecture & Implementation

A portable UUIDv7 generator for Clojure, ClojureScript, Babashka, nbb, and Scittle.
Implements RFC 9562 §6.2 Method 3 (monotonic random).

---

## Why UUIDv7?

UUIDv7 encodes a millisecond-precision Unix timestamp in the high bits of a standard UUID. This gives you three things v4 doesn't: **lexicographic sortability**, **temporal ordering**, and **database-friendly sequential insertion** — all while maintaining the same uniqueness guarantees. For primary keys, event IDs, log correlation, and audit trails, there's no reason to use v4 anymore.

The embedded timestamp means you can extract *when* something was created directly from the ID — no extra column, no lookup. That's particularly useful for logging and audit, where you often have only an ID and need to know when it happened.

**When UUIDv7 is not the right choice:**

- **Deterministic / content-addressed IDs** — use v5. Given the same namespace + input, v5 always produces the same UUID. UUIDv7 generates a new value every call. These are fundamentally different tools.
- **Privacy-sensitive tokens** — use v4. UUIDv7 leaks the creation timestamp to millisecond precision. Anyone who sees the ID knows *when* it was created. For session tokens, API keys, or anything user-facing where timing information reveals usage patterns, v4 (pure random) remains the better choice.

---

## RFC 9562 — The Three Methods

The RFC defines three compliant approaches for populating the 74 random bits (12-bit `rand_a` + 62-bit `rand_b`):

**Method 1: Pure Random (stateless)**
Fill all 74 bits with fresh crypto-random on every call. Zero state, trivially safe across threads and processes. But IDs generated within the same millisecond have *no guaranteed ordering* — they sort randomly within that 1ms bucket. Fine for distributed systems that don't need sub-millisecond monotonicity.

**Method 2: Fixed 12-bit Counter**
Use the 12-bit `rand_a` field as a counter (seeded randomly each new millisecond), keep `rand_b` fully random. Gives you monotonic ordering within a millisecond — but only 4,096 IDs per ms before overflow. At ~4 million IDs/sec that's enough for most workloads, but overflow requires a policy decision: block until next ms, return an error, or silently fall back to random (breaking monotonicity).

**Method 3: Monotonic Random (74-bit counter space)** ← *this implementation*
Seed all 74 bits randomly at each new millisecond. Within the same millisecond, increment by a random amount. The full 74-bit space gives ~1.9 × 10²² values per millisecond — effectively inexhaustible. No blocking, no spinning, no overflow in any realistic scenario. On the astronomically unlikely overflow, the implementation advances the timestamp by 1ms and reseeds, preserving monotonicity.

### Why Method 3?

If you're choosing UUIDv7 over v4, you almost certainly want sortable, monotonic IDs. Method 1 doesn't give you that within a millisecond. Method 2 gives it but introduces an overflow cliff at 4K/ms that demands a policy decision — block (adds latency), abort (pushes complexity to callers), or fall back to random (silently breaks the promise).

Method 3 eliminates the tradeoff entirely. The 74-bit counter space is so vast that overflow is a theoretical concern, not a practical one. And when you increment by a random amount rather than +1, you also prevent leaking your generation rate and reduce collision risk between independent generators.

---

## Counter Design

### The Problem with 64-bit Arithmetic in JavaScript

JavaScript numbers are IEEE 754 doubles — they have 53 bits of integer precision. The 74-bit counter can't fit in a single JS number. You also can't use bit-shift operators (they truncate to 32 bits in JS).

### The Solution: Three-Field Split

The 74-bit counter is represented as three independent fields, each fitting comfortably within JS safe-integer range:

| Field        | Bits  | Range              | Maps to        |
|-------------|-------|--------------------|----------------|
| `rand-a`    | 12    | 0 – 4,095          | UUID `rand_a`  |
| `rand-b-hi` | 30    | 0 – 1,073,741,823  | `rand_b` high  |
| `rand-b-lo` | 32    | 0 – 4,294,967,295  | `rand_b` low   |

Carry propagation uses `quot`/`rem` (not bit-shifts), which is correct on both JVM longs and JS doubles:

```
sum-lo   = rand-b-lo + increment
carry-hi = quot(sum-lo, 2^32)
new-lo   = rem(sum-lo, 2^32)

sum-hi   = rand-b-hi + carry-hi
carry-a  = quot(sum-hi, 2^30)
new-hi   = rem(sum-hi, 2^30)

new-a    = rand-a + carry-a
```

If `new-a >= 4096`, that's a 74-bit overflow. The implementation handles it by bumping the timestamp to `ts + 1` and reseeding — which is safe because the new timestamp is strictly greater, so monotonicity is preserved.

### Random Increment

Within the same millisecond, the counter increments by a random value in `[1, 2^31]`. This serves two purposes:

1. **Unpredictability** — incrementing by +1 would leak how many IDs you've generated. A random step preserves the unpredictability of the lower bits.
2. **Inter-generator safety** — two independent generators seeded at different random positions are unlikely to collide even if they happen to start in the same millisecond, because their increments are also random.

---

## Randomness Source

The implementation uses `random-uuid` (Clojure's built-in) as its portable CSPRNG. This is the *one* crypto-random primitive available on all five target platforms:

| Platform    | `random-uuid` backed by                  |
|------------|------------------------------------------|
| Clojure    | `java.util.UUID/randomUUID` (SecureRandom) |
| Babashka   | `java.util.UUID/randomUUID` (SecureRandom) |
| ClojureScript | `crypto.getRandomValues`              |
| nbb        | `crypto.getRandomValues` (Node.js)       |
| Scittle    | `crypto.getRandomValues` (browser)       |

A v4 UUID has 122 random bits. By generating two, we get 244+ bits of entropy — far more than the 74 we need for the counter plus the ~31 for the increment. We extract from hex positions that are known to be fully random (avoiding the v4 version nibble at position 12 and the variant bits at position 16).

This is slightly wasteful (generating 256 bits to use 105), but it's simple, correct, dependency-free, and fast enough — UUID generation is not typically a bottleneck.

---

## Clock Rollback

If the system clock jumps backward (NTP correction, VM migration, manual adjustment), `now` will be less than the stored `ts`. The implementation handles this by **not updating the timestamp** — it keeps the old `ts` and increments the counter as if it were the same millisecond. This preserves monotonicity at the cost of the timestamp being slightly in the future until the clock catches up.

This is the standard approach recommended by RFC 9562 and used by all major implementations.

---

## Concurrency

State is held in a Clojure `atom`, updated via `swap!`. This gives you:

- **JVM/Babashka**: Lock-free CAS (compare-and-swap). Multiple threads can call `uuidv7` concurrently; `swap!` retries on contention. No locks, no blocking.
- **ClojureScript/nbb/Scittle**: Single-threaded event loop, so `swap!` is effectively just a mutation. No contention possible.

For high-contention JVM workloads where CAS retries become measurable, use `make-generator` to create per-thread dedicated generators — each has its own atom, its own monotonic sequence, no cross-thread contention.

---

## UUID Construction

The UUIDv7 bit layout (128 bits total):

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                    unix_ts_ms (32 high bits)                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| unix_ts_ms (16 low bits)      |  ver=0111  |    rand_a       |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|var=10|                     rand_b                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         rand_b (cont.)                        |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

**JVM path**: Computes two `long` values (`msb`, `lsb`) and calls `java.util.UUID.`'s two-argument constructor directly. No string allocation or parsing.

**CLJS path**: Builds the 32-character hex string with dashes and passes it to `parse-uuid`. Uses `quot`/`rem` for the timestamp split to stay within safe-integer range. Note: the `uuid` constructor function exists in ClojureScript but is not mapped to the `uuid` var in scittle — a one-liner fix in scittle's core would resolve this, but `parse-uuid` works on all platforms and is the safer portable choice.

Both paths produce platform-native UUID types that print as `#uuid "..."`, compare correctly, and work with all standard serialization.

---

## API Reference

### `(uuidv7)`

Generate a UUIDv7 with monotonic sub-millisecond ordering.

```clojure
(require '[uuidv7.core :refer [uuidv7]])

(uuidv7)
;=> #uuid "01950a3e-8b2f-7a1c-8e4d-3f2b1a0c9d8e"

;; Successive calls are strictly increasing, even within the same ms:
(let [a (uuidv7) b (uuidv7)]
  (neg? (compare a b)))
;=> true (a < b, always)
```

Returns `java.util.UUID` on JVM/Babashka, `cljs.core/UUID` on ClojureScript/nbb/Scittle.

### `(make-generator)`

Create an independent generator with its own monotonic state. Returns a zero-argument function.

```clojure
(let [gen (make-generator)]
  (gen)  ;=> #uuid "..."
  (gen)) ;=> #uuid "..." (strictly greater than previous)
```

Useful for per-thread or per-subsystem dedicated sequences that don't contend on the shared global atom.

### `(extract-ts uuid)`

Extract the Unix epoch timestamp (milliseconds) from any UUIDv7.

```clojure
(extract-ts #uuid "01950a3e-8b2f-7a1c-8e4d-3f2b1a0c9d8e")
;=> 1738934578991
```

Works with UUID objects or UUID strings.

### `(extract-counter uuid)`

Extract the 74-bit monotonic counter as a three-element vector `[rand-a rand-b-hi rand-b-lo]`.

```clojure
(extract-counter #uuid "01950a3e-8b2f-7a1c-8e4d-3f2b1a0c9d8e")
;=> [2844 14925 1058348430]
```

The vector compares lexicographically, preserving the same total order as the UUID itself. Same shape on all platforms — the three-field split (12 + 30 + 32 bits) keeps each value within JS safe-integer range while maintaining correct comparison semantics everywhere.

### `(extract-key uuid)`

Extract a sortable composite key `[ts rand-a rand-b-hi rand-b-lo]` — the full (timestamp, counter) tuple.

```clojure
(extract-key #uuid "01950a3e-8b2f-7a1c-8e4d-3f2b1a0c9d8e")
;=> [1738934578991 2844 14925 1058348430]

;; Use as a map key or sort key:
(sorted-map-by compare
  (extract-key id-a) :first
  (extract-key id-b) :second)
```

This is the primary function when you want the (timestamp, counter) tuple as a key without carrying the UUID itself. The four-element vector preserves the exact same total order as UUID comparison.

### `(extract-inst uuid)`

Extract the creation timestamp as a `#inst` Date.

```clojure
(extract-inst #uuid "01950a3e-8b2f-7a1c-8e4d-3f2b1a0c9d8e")
;=> #inst "2025-02-07T12:22:58.991-00:00"
```

Particularly useful for logging and audit — you can reconstruct *when* any entity was created directly from its ID without touching a database.

---

## Platform Compatibility

| Platform       | UUID type          | Random source     | Tested |
|---------------|-------------------|-------------------|--------|
| Clojure (JVM) | `java.util.UUID`  | SecureRandom      | ✓      |
| Babashka      | `java.util.UUID`  | SecureRandom      | ✓      |
| ClojureScript | `cljs.core/UUID`  | crypto.getRandomValues | ✓ |
| nbb           | `cljs.core/UUID`  | crypto.getRandomValues | ✓ |
| Scittle/SCI   | `cljs.core/UUID`  | crypto.getRandomValues | ✓ |

The `.cljc` reader conditionals have two main branches: `:clj` (covers JVM + Babashka) and `:cljs` (covers ClojureScript + nbb + Scittle). A third branch, `:scittle`, is used solely at the end of `core.cljc` to reset `*ns*` back to `user` after the library loads — this is invisible to all other platforms. No external dependencies.

---

## Counter Extraction and Composite Keys

A common pattern is using the `(timestamp, counter)` tuple as a composite key instead of the full UUID — for example in sorted maps, database indexes, or interop with systems that don't natively support UUIDs.

Worth noting: if string comparison is acceptable, **the UUIDv7 string itself is already a perfectly good sortable key**. The timestamp sits in the high bits, so `(compare (str u1) (str u2))` preserves the same temporal and monotonic order. The extraction functions below are for when you want the numeric components separately — for arithmetic, range queries on the timestamp, or storage in systems where a UUID string isn't the natural key type.

### Why a Three-Element Vector Everywhere?

On the JVM, the 62-bit `rand_b` fits in a single `long`, so a two-element `[rand-a rand-b]` would suffice. On JS, it doesn't — 62 bits exceeds the 53-bit safe-integer limit, so it must be split into `[rand-b-hi rand-b-lo]`.

We chose **consistent shape across all platforms**: `[rand-a rand-b-hi rand-b-lo]` everywhere. The alternative — returning `[rand-a rand-b]` on JVM and `[rand-a rand-b-hi rand-b-lo]` on CLJS — would leak the platform abstraction into every consumer. Any code that destructures, compares, stores, or serializes the counter would need reader conditionals. The whole point of `.cljc` is to avoid that.

The three-element vector is harmless on JVM (the split is unnecessary but costs nothing) and the consistency means portable code just works.

### UUIDv7 Strings as Sortable Keys

In many cases, you don't need to extract components at all — **`(str uuid)` is already a perfectly sortable key** that preserves generation order. The timestamp occupies the high bits of the UUID, so string comparison (`compare`, alphabetical sort, database indexing) yields the same temporal and monotonic ordering as the original UUIDs. This works across all platforms, serialization formats, and storage systems that support string keys.

---

## Guarantees and Limitations

**Guaranteed:**
- Strictly monotonic ordering from a single generator instance (including within the same millisecond)
- RFC 9562 compliant (version 7, variant 10xx)
- Cryptographic-quality randomness on all platforms
- Correct timestamps extractable from generated IDs

**Not guaranteed:**
- Ordering across independent generators (different `make-generator` instances, different processes, different machines). They will *likely* not collide but are not *coordinated*.
- Monotonicity if you deserialize and re-compare UUIDs across JVM and CLJS (the underlying comparison semantics of `java.util.UUID` vs `cljs.core/UUID` may differ for edge cases in the variant/version bits).
- Sub-millisecond *timestamp* precision. The timestamp is millisecond-granularity; sub-ms ordering comes from the monotonic counter, not from a finer clock.
