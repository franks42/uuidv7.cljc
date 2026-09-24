(ns com.github.franks42.uuidv7.core
  "Portable UUIDv7 generator for Clojure, ClojureScript, Babashka, nbb, and scittle.

   Implements RFC 9562 Section 6.2 Method 3 (monotonic random):
   - 48-bit millisecond Unix timestamp
   - 74-bit monotonically increasing random counter
   - Sub-millisecond ordering guaranteed from a single generator
   - No blocking, no spinning, no overflow in practice

   Usage:
     (require '[com.github.franks42.uuidv7.core :as uuidv7])
     (uuidv7/uuidv7)  ;=> #uuid \"0195xxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx\"

     ;; Extract embedded data:
     (uuidv7/extract-ts   u)  ;=> 1738934578991        (ms since epoch)
     (uuidv7/extract-inst u)  ;=> #inst \"2025-02-07...\" (as Date)
     (uuidv7/extract-key  u)  ;=> [ts a bh bl]          (sortable composite key)

   The 74-bit counter space (~1.9 * 10^22 values per millisecond)
   is effectively inexhaustible. On each new millisecond the counter
   reseeds with fresh random bits. Within the same millisecond it
   increments by a random amount (1 to 2^31), preserving both
   monotonicity and unpredictability.

   ## UUID Validation

   The extraction functions (`extract-ts`, `extract-counter`, `extract-key`,
   `extract-inst`) require a UUIDv7. Use `uuidv7?` to validate first:

     (when (uuidv7/uuidv7? u)
       (uuidv7/extract-ts u))  ;=> Safe to call after validation

   Passing anything else to an extraction function throws an ex-info
   with `{:type ::not-uuidv7}` in its ex-data."
  #?(:clj (:import [java.util UUID])))

;; Version of this library. Updated at release time; matches the
;; Maven coord on Clojars and the version reported by bin/uuidv7
;; --version. Keep this as a single-line def — the release workflow
;; greps for the version constant and breaks on multi-line forms.
(def version "0.7.2")

;; ---------------------------------------------------------------------------
;; Platform helpers
;; ---------------------------------------------------------------------------

(defn- now-ms
  "Current Unix epoch time in milliseconds. Impure: reads the clock."
  []
  #?(:clj  (System/currentTimeMillis)
     :cljs (js/Date.now)))

(defn- parse-hex
  "Parse a hexadecimal string to a platform integer."
  [s]
  #?(:clj  (Long/parseLong s 16)
     :cljs (js/parseInt s 16)))

(defn- to-hex
  "Format a non-negative integer as a zero-padded lowercase hex string."
  [n width]
  (let [s   #?(:clj  (Long/toHexString (long n))
               :cljs (.toString (js/Math.trunc n) 16))
        pad (- width (count s))]
    (if (pos? pad)
      (str (subs "0000000000000000" 0 pad) s)
      s)))

;; ---------------------------------------------------------------------------
;; Random number generation — the platform CSPRNG, called directly
;;
;;   CLJ / BB             → java.security.SecureRandom (OS generator)
;;   CLJS / nbb / Scittle → crypto.getRandomValues   (OS generator)
;;
;; Not random-uuid: cljs.core/random-uuid is built on Math.random, which is
;; not cryptographically secure (uuidv7 used it until 0.7.1).
;; ---------------------------------------------------------------------------

#?(:clj
   (defonce ^:private ^java.security.SecureRandom secure-random
     (java.security.SecureRandom.)))

#?(:cljs
   (def ^:private max-random-chunk
     "crypto.getRandomValues fills at most 65,536 bytes per call."
     65536))

(defn random-bytes
  "Impure: draws from the platform's CSPRNG.

   Returns n bytes from the platform's cryptographically secure generator:
   a byte[] from java.security.SecureRandom on the JVM and bb, a Uint8Array
   from crypto.getRandomValues on ClojureScript, nbb and Scittle.

   Fails closed: throws if no secure generator is available, rather than
   falling back to Math.random."
  [n]
  #?(:clj  (let [bs (byte-array n)]
             (.nextBytes ^java.security.SecureRandom secure-random bs)
             bs)
     :cljs (let [c (.-crypto js/globalThis)]
             (when-not (and c (fn? (.-getRandomValues c)))
               (throw (ex-info "No secure random generator (crypto.getRandomValues) available"
                               {:type ::no-secure-random})))
             (let [out (js/Uint8Array. n)]
               (loop [off 0]
                 (when (< off n)
                   (.getRandomValues c (.subarray out off (min n (+ off max-random-chunk))))
                   (recur (+ off max-random-chunk))))
               out))))

(defn- bytes->uint
  "Unsigned big-endian integer from k bytes of bs at offset off (k <= 6, so
   the result stays within JS safe-integer range)."
  [bs off k]
  (reduce (fn [acc i] (+ (* acc 256) (bit-and (aget bs (+ off i)) 0xFF)))
          0
          (range k)))

(def ^:private random-byte-count
  "Random bytes one generator step consumes: 10 to seed the 74-bit counter
   on a new millisecond, 4 for the same-millisecond increment."
  14)

(defn- seed-fields
  "The 74-bit counter seeded from bytes 0-9 of rnd, as
   [rand-a rand-b-hi rand-b-lo] (12, 30 and 32 bits). Mod by a power of two
   keeps each field uniform. Pure."
  [rnd]
  [(mod (bytes->uint rnd 0 2) 4096)          ;; 12 of 16 bits
   (mod (bytes->uint rnd 2 4) 1073741824)    ;; 30 of 32 bits
   (bytes->uint rnd 6 4)])                   ;; 32 bits

(defn- increment
  "The same-millisecond increment from bytes 10-13 of rnd: in [1, 2^31],
   within JS integer precision and large enough to keep the counter
   unpredictable. Pure."
  [rnd]
  (inc (mod (bytes->uint rnd 10 4) 2147483648)))

;; ---------------------------------------------------------------------------
;; Generator state
;;
;; The 74 counter bits are split into three fields that individually
;; stay within JS safe-integer range:
;;
;;   :rand-a    12 bits   (bits 73–62 of the counter)
;;   :rand-b-hi 30 bits   (bits 61–32)
;;   :rand-b-lo 32 bits   (bits 31–0)
;;
;; Arithmetic uses quot/rem instead of bit-shifts so it is correct
;; on both 64-bit JVM longs and JS 53-bit-safe doubles.
;; ---------------------------------------------------------------------------

(defonce ^:private state
  (atom {:ts 0 :rand-a 0 :rand-b-hi 0 :rand-b-lo 0}))

(defn- next-state
  "The generator state after `state`, for clock reading `now` and 14 random
   bytes `rnd`. Pure: the caller reads the clock and draws the bytes, so
   this function can run inside swap! (which may retry) and has
   known-answer tests.
   - now > ts  → new millisecond: seed the counter from rnd.
   - now <= ts → same ms (or clock rollback): increment the counter, keep ts.
   On the astronomically unlikely 74-bit overflow, advance ts by 1 and
   reseed."
  [{:keys [ts rand-a rand-b-hi rand-b-lo]} now rnd]
  (when-not (and (some? rnd) (= random-byte-count (alength rnd)))
    (throw (ex-info (str "next-state needs exactly " random-byte-count " random bytes")
                    {:type ::bad-random-bytes})))
  (if (> now ts)
    ;; ---- new millisecond ----
    (let [[a bh bl] (seed-fields rnd)]
      {:ts now :rand-a a :rand-b-hi bh :rand-b-lo bl})
    ;; ---- same / earlier ms — increment 74-bit counter ----
    (let [inc-val  (increment rnd)
          sum-lo   (+ rand-b-lo inc-val)
          carry-hi (quot sum-lo 4294967296)               ;; 2^32
          new-lo   (rem  sum-lo 4294967296)
          sum-hi   (+ rand-b-hi carry-hi)
          carry-a  (quot sum-hi 1073741824)               ;; 2^30
          new-hi   (rem  sum-hi 1073741824)
          new-a    (+ rand-a carry-a)]
      (if (>= new-a 4096)
        ;; overflow — advance timestamp, reseed (cannot break monotonicity
        ;; because the new ts is strictly greater than the old ts)
        (let [[a bh bl] (seed-fields rnd)]
          {:ts (inc ts) :rand-a a :rand-b-hi bh :rand-b-lo bl})
        {:ts ts :rand-a new-a :rand-b-hi new-hi :rand-b-lo new-lo}))))

;; ---------------------------------------------------------------------------
;; UUID construction
;;
;; UUIDv7 layout (128 bits):
;;
;;   0                   1                   2                   3
;;   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
;;  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
;;  |                    unix_ts_ms (32 high bits)                  |
;;  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
;;  | unix_ts_ms (16 low bits)      | ver (0111) |    rand_a       |
;;  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
;;  |var(10)|                     rand_b                            |
;;  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
;;  |                         rand_b (cont.)                        |
;;  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
;; ---------------------------------------------------------------------------

(defn- state->uuid
  "Construct a platform-native UUID from generator state. Pure."
  [{:keys [ts rand-a rand-b-hi rand-b-lo]}]
  #?(:clj
     ;; JVM / Babashka — use the two-long constructor for efficiency
     (let [msb    (bit-or (bit-shift-left (long ts) 16)
                          (bit-or 0x7000 (long rand-a)))
           rand-b (bit-or (bit-shift-left (long rand-b-hi) 32)
                          (long rand-b-lo))
           lsb    (bit-or Long/MIN_VALUE rand-b)]     ;; MIN_VALUE = 0x8000000000000000 → sets variant "10"
       (UUID. msb lsb))

     :cljs
     ;; ClojureScript / nbb / scittle — build the hex string
     ;; Groups: 8-4-4-4-12
     ;;   g1  = ts bits 47–16          (8 hex)
     ;;   g2  = ts bits 15–0           (4 hex)
     ;;   g3  = version 7 + rand_a     (4 hex)
     ;;   g4  = variant 10 + rand_b hi (4 hex)
     ;;   g5  = rand_b lo              (12 hex)
     (parse-uuid
      (str (to-hex (quot ts 65536)  8)                                ;; g1
           "-"
           (to-hex (rem ts 65536) 4)                                  ;; g2
           "-"
           (to-hex (+ 0x7000 rand-a) 4)                               ;; g3
           "-"
           (to-hex (+ 0x8000 (quot rand-b-hi 65536)) 4)               ;; g4
           "-"
           (to-hex (rem rand-b-hi 65536) 4)                           ;; g5 hi
           (to-hex rand-b-lo 8)))))                                    ;; g5 lo

;; ---------------------------------------------------------------------------
;; UUID validation
;; ---------------------------------------------------------------------------

;; Canonical 8-4-4-4-12 hex form, version digit 7, variant 10xx (8/9/a/b).
;; Either case, per RFC 9562 §4. Explicit A-F ranges rather than a (?i)
;; flag, which not every target's regex reader supports.
(def ^:private uuidv7-re
  #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-7[0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}")

(defn uuidv7?
  "True if `uuid` is a version 7, variant 10xx UUID.
   Accepts UUID objects and strings in the canonical 8-4-4-4-12 hex form,
   in either case. Anything else — nil, other types, other string forms
   such as `urn:uuid:...` or braces — returns false; never throws."
  [uuid]
  (boolean (re-matches uuidv7-re (str uuid))))

(defn- check-uuidv7
  "Throw unless `uuid` is a UUIDv7. An ex-info rather than an assert:
   asserts can be compiled out, and AssertionError escapes a
   (catch Exception ...)."
  [fname uuid]
  (when-not (uuidv7? uuid)
    (throw (ex-info (str fname ": not a UUIDv7: " (pr-str uuid))
                    {:type ::not-uuidv7 :value uuid}))))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn- advance!
  "The imperative shell around next-state: read the clock and draw the
   random bytes first, then advance the generator atom a. The swap! update
   function is pure, so a retry under contention cannot draw twice or read
   the clock twice. Impure: clock, CSPRNG, mutates a."
  [a]
  (let [now (now-ms)
        rnd (random-bytes random-byte-count)]
    (state->uuid (swap! a next-state now rnd))))

(defn uuidv7
  "Generate a UUIDv7 with monotonic sub-millisecond ordering.

   Impure: reads the clock and the CSPRNG, and advances the default
   generator's state.

   Returns java.util.UUID on JVM/BB, cljs.core/UUID on CLJS/nbb/scittle.

   Successive calls from the same generator are guaranteed to produce
   strictly increasing UUIDs, even within the same millisecond."
  []
  (advance! state))

(defn make-generator
  "Create an independent UUIDv7 generator with its own monotonic state.
   Returns a zero-argument function that produces UUIDv7s.

   The returned function is impure in the same way as uuidv7: clock,
   CSPRNG, and its own state.

   Useful when you need multiple independent monotonic sequences,
   e.g. per-subsystem or per-thread dedicated generators."
  []
  (let [gen-state (atom {:ts 0 :rand-a 0 :rand-b-hi 0 :rand-b-lo 0})]
    (fn [] (advance! gen-state))))

(defn extract-ts
  "Extract the Unix epoch timestamp (milliseconds) from a UUIDv7.
   Works with any UUID type or UUID string.

   Throws ex-info {:type ::not-uuidv7} if the UUID is not version 7."
  [uuid]
  (check-uuidv7 "extract-ts" uuid)
  (let [s (str uuid)]
    (parse-hex (str (subs s 0 8) (subs s 9 13)))))

(defn extract-counter
  "Extract the 74-bit monotonic counter from a UUIDv7 as a three-element
   vector [rand-a rand-b-hi rand-b-lo] (12 + 30 + 32 bits).

   The vector compares lexicographically, preserving the same total order
   as the original UUID. Suitable as a composite key component:
     [(extract-ts u) (extract-counter u)]

   Consistent shape on all platforms (JVM and JS).

   Throws ex-info {:type ::not-uuidv7} if the UUID is not version 7."
  [uuid]
  (check-uuidv7 "extract-counter" uuid)
  (let [s (str uuid)]
    [(parse-hex (subs s 15 18))                                   ;; rand-a:    3 hex = 12 bits
     (+ (* (bit-and (parse-hex (subs s 19 23)) 0x3FFF) 65536)     ;; rand-b-hi: 14 bits from g4
        (parse-hex (subs s 24 28)))                                ;;          + 16 bits from g5 hi = 30 bits
     (parse-hex (subs s 28 36))]))                                 ;; rand-b-lo: 8 hex = 32 bits

(defn extract-key
  "Extract a sortable composite key [ts rand-a rand-b-hi rand-b-lo]
   from a UUIDv7.

   The four-element vector compares lexicographically with the same
   total order as the original UUID. Useful when you want the
   (timestamp, counter) tuple as a map key or sort key without
   carrying the UUID itself.

   Throws ex-info {:type ::not-uuidv7} if the UUID is not version 7."
  [uuid]
  (check-uuidv7 "extract-key" uuid)
  (into [(extract-ts uuid)] (extract-counter uuid)))

(defn extract-inst
  "Extract the creation timestamp from a UUIDv7 as a Date/inst.
   Useful for logging, auditing, and debugging.

   Throws ex-info {:type ::not-uuidv7} if the UUID is not version 7."
  [uuid]
  (check-uuidv7 "extract-inst" uuid)
  (let [ts (extract-ts uuid)]
    #?(:clj  (java.util.Date. (long ts))
       :cljs (js/Date. ts))))

;; When loaded by scittle (SCI), evaluating the ns form leaves *ns*
;; set to this library's namespace.  Reset to 'user so that callers
;; can use a plain (require … :as alias) instead of a full ns form.
;; The :scittle reader conditional is only active in scittle — it is
;; invisible to CLJ, CLJS, BB, and nbb.
#?(:scittle (in-ns 'user))
