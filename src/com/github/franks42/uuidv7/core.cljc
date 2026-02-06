(ns com.github.franks42.uuidv7.core
  "Portable UUIDv7 generator for Clojure, ClojureScript, Babashka, nbb, and scittle.

   Implements RFC 9562 Section 6.2 Method 3 (monotonic random):
   - 48-bit millisecond Unix timestamp
   - 74-bit monotonically increasing random counter
   - Sub-millisecond ordering guaranteed from a single generator
   - No blocking, no spinning, no overflow in practice

   Usage:
     (require '[com.github.franks42.uuidv7.core :refer [uuidv7]])
     (uuidv7)  ;=> #uuid \"0195xxxx-xxxx-7xxx-xxxx-xxxxxxxxxxxx\"

     ;; Extract embedded data:
     (extract-ts   u)  ;=> 1738934578991        (ms since epoch)
     (extract-inst u)  ;=> #inst \"2025-02-07...\" (as Date)
     (extract-key  u)  ;=> [ts a bh bl]          (sortable composite key)

   The 74-bit counter space (~1.9 * 10^22 values per millisecond)
   is effectively inexhaustible. On each new millisecond the counter
   reseeds with fresh random bits. Within the same millisecond it
   increments by a random amount (1 to 2^31), preserving both
   monotonicity and unpredictability."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.util UUID])))

;; ---------------------------------------------------------------------------
;; Platform helpers
;; ---------------------------------------------------------------------------

(defn- now-ms
  "Current Unix epoch time in milliseconds."
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
;; Random number generation — uses random-uuid as a portable CSPRNG source
;;
;; random-uuid is available on all targets:
;;   CLJ  → java.util.UUID/randomUUID (SecureRandom)
;;   CLJS → crypto.getRandomValues
;;   BB   → java.util.UUID/randomUUID
;;   nbb  → CLJS crypto.getRandomValues
;;   sci  → CLJS crypto.getRandomValues
;; ---------------------------------------------------------------------------

(defn- random-bits
  "Generate random values for the 74-bit counter:
     rand-a    — 12 bits  [0, 4095]
     rand-b-hi — 30 bits  [0, 1073741823]
     rand-b-lo — 32 bits  [0, 4294967295]
   Returns [rand-a rand-b-hi rand-b-lo]."
  []
  ;; Two UUIDs give us 244+ random bits — more than the 74 we need.
  ;; We extract from hex positions known to be fully random
  ;; (avoiding the v4 version digit at position 12 and variant at 16).
  (let [h1 (str/replace (str (random-uuid)) "-" "")
        h2 (str/replace (str (random-uuid)) "-" "")]
    [(parse-hex (subs h1 0 3))                          ;; 12 bits
     (bit-and (parse-hex (subs h1 3 11)) 0x3FFFFFFF)    ;; 30 bits
     (parse-hex (subs h2 0 8))]))                        ;; 32 bits

(defn- random-increment
  "Random increment in [1, 2^31]. Safe on all platforms (within JS
   integer precision) and large enough to preserve unpredictability."
  []
  (let [hex (subs (str/replace (str (random-uuid)) "-" "") 0 8)]
    (inc (bit-and (parse-hex hex) 0x7FFFFFFF))))

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
  "Advance the generator state for timestamp `now`.
   - now > ts  → new millisecond: seed fresh random bits.
   - now <= ts → same (or clock rollback): increment counter, keep ts.
   On the astronomically unlikely 74-bit overflow, advance ts by 1."
  [{:keys [ts rand-a rand-b-hi rand-b-lo]} now]
  (if (> now ts)
    ;; ---- new millisecond ----
    (let [[a bh bl] (random-bits)]
      {:ts now :rand-a a :rand-b-hi bh :rand-b-lo bl})
    ;; ---- same / earlier ms — increment 74-bit counter ----
    (let [inc-val  (random-increment)
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
        (let [[a bh bl] (random-bits)]
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
  "Construct a platform-native UUID from generator state."
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
;; Public API
;; ---------------------------------------------------------------------------

(defn uuidv7
  "Generate a UUIDv7 with monotonic sub-millisecond ordering.

   Returns java.util.UUID on JVM/BB, cljs.core/UUID on CLJS/nbb/scittle.

   Successive calls from the same generator are guaranteed to produce
   strictly increasing UUIDs, even within the same millisecond."
  []
  (let [now (now-ms)]
    (state->uuid (swap! state next-state now))))

(defn make-generator
  "Create an independent UUIDv7 generator with its own monotonic state.
   Returns a zero-argument function that produces UUIDv7s.

   Useful when you need multiple independent monotonic sequences,
   e.g. per-subsystem or per-thread dedicated generators."
  []
  (let [gen-state (atom {:ts 0 :rand-a 0 :rand-b-hi 0 :rand-b-lo 0})]
    (fn []
      (let [now (now-ms)]
        (state->uuid (swap! gen-state next-state now))))))

(defn extract-ts
  "Extract the Unix epoch timestamp (milliseconds) from a UUIDv7.
   Works with any UUID type or UUID string."
  [uuid]
  (let [s (str uuid)]
    (parse-hex (str (subs s 0 8) (subs s 9 13)))))

(defn extract-counter
  "Extract the 74-bit monotonic counter from a UUIDv7 as a three-element
   vector [rand-a rand-b-hi rand-b-lo] (12 + 30 + 32 bits).

   The vector compares lexicographically, preserving the same total order
   as the original UUID. Suitable as a composite key component:
     [(extract-ts u) (extract-counter u)]

   Consistent shape on all platforms (JVM and JS)."
  [uuid]
  (let [s (str uuid)]
    [(parse-hex (subs s 15 18))                                   ;; rand-a:    3 hex = 12 bits
     (+ (* (bit-and (parse-hex (subs s 19 23)) 0x3FFF) 65536)     ;; rand-b-hi: 14 bits from g4
        (parse-hex (subs s 24 28)))                                ;;          + 16 bits from g5 hi = 30 bits
     (parse-hex (subs s 28 36))]))                                 ;; rand-b-lo: 8 hex = 32 bits

(defn extract-counter-hex
  "Extract the 74-bit monotonic counter from a UUIDv7 as a 19-character
   zero-padded lowercase hex string.

   String comparison preserves the same total order as the original UUID.
   Useful for storage in systems that prefer string keys."
  [uuid]
  (let [[a bh bl] (extract-counter uuid)]
    (str (to-hex a 3) (to-hex bh 8) (to-hex bl 8))))

(defn extract-key
  "Extract a sortable composite key [ts rand-a rand-b-hi rand-b-lo]
   from a UUIDv7.

   The four-element vector compares lexicographically with the same
   total order as the original UUID. Useful when you want the
   (timestamp, counter) tuple as a map key or sort key without
   carrying the UUID itself."
  [uuid]
  (into [(extract-ts uuid)] (extract-counter uuid)))

(defn extract-inst
  "Extract the creation timestamp from a UUIDv7 as a Date/inst.
   Useful for logging, auditing, and debugging."
  [uuid]
  (let [ts (extract-ts uuid)]
    #?(:clj  (java.util.Date. (long ts))
       :cljs (js/Date. ts))))
