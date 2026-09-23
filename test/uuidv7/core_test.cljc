(ns uuidv7.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [com.github.franks42.uuidv7.core :as uuidv7]))

(defn uuid<?
  "Compare two UUIDs for less-than ordering."
  [u1 u2]
  (neg? (compare u1 u2)))

(deftest test-uuidv7-generation
  (testing "uuidv7 generates valid UUIDs"
    (let [u (uuidv7/uuidv7)]
      (is (uuid? u))
      (is (not (nil? u)))))

  (testing "UUIDs are version 7"
    (let [u (uuidv7/uuidv7)
          s (str u)]
      ;; Version is in character 14 (0-indexed), 4th char of 3rd group
      (is (= "7" (subs s 14 15)))))

  (testing "UUIDs have variant 10xx"
    (let [u (uuidv7/uuidv7)
          s (str u)]
      ;; Variant bits are in character 19 (0-indexed), first char of 4th group
      (is (#{"8" "9" "a" "b"} (subs s 19 20)))))

  (testing "Successive calls produce strictly increasing UUIDs"
    (let [u1 (uuidv7/uuidv7)
          u2 (uuidv7/uuidv7)]
      (is (uuid<? u1 u2))))

  (testing "Multiple calls within same millisecond are strictly increasing"
    (let [us (doall (map (fn [_] (uuidv7/uuidv7)) (range 10)))]
      (is (every? true? (map uuid<? us (rest us)))))))

(deftest test-extract-ts
  (testing "extract-ts returns the embedded timestamp"
    (let [u (uuidv7/uuidv7)
          ts (uuidv7/extract-ts u)
          now #?(:clj  (System/currentTimeMillis)
                 :cljs (js/Date.now))]
      ;; Timestamp should be within last second
      (is (<= (- now 1000) ts now))))

  (testing "extract-ts works with UUID strings"
    (let [u (uuidv7/uuidv7)
          ts1 (uuidv7/extract-ts u)
          ts2 (uuidv7/extract-ts (str u))]
      (is (= ts1 ts2)))))

(deftest test-extract-counter
  (testing "extract-counter returns three-element vector"
    (let [u (uuidv7/uuidv7)
          counter (uuidv7/extract-counter u)]
      (is (vector? counter))
      (is (= 3 (count counter)))))

  (testing "Counter components are in valid ranges"
    (let [u (uuidv7/uuidv7)
          [a bh bl] (uuidv7/extract-counter u)]
      (is (<= 0 a 4095))
      (is (<= 0 bh 1073741823))
      (is (<= 0 bl 4294967295)))))

(deftest test-extract-key
  (testing "extract-key returns four-element vector"
    (let [u (uuidv7/uuidv7)
          key (uuidv7/extract-key u)]
      (is (vector? key))
      (is (= 4 (count key)))))

  (testing "Keys compare correctly"
    (let [u1 (uuidv7/uuidv7)
          u2 (uuidv7/uuidv7)
          k1 (uuidv7/extract-key u1)
          k2 (uuidv7/extract-key u2)]
      (is (uuid<? k1 k2))
      (is (uuid<? u1 u2)))))

(deftest test-extract-inst
  (testing "extract-inst returns a Date"
    (let [u (uuidv7/uuidv7)
          inst (uuidv7/extract-inst u)]
      (is (instance? #?(:clj java.util.Date :cljs js/Date) inst)))))

(deftest test-monotonicity
  (testing "make-generator creates independent generator"
    (let [gen1 (uuidv7/make-generator)
          gen2 (uuidv7/make-generator)
          u1a (gen1)
          u1b (gen1)
          u2a (gen2)]
      (is (uuid<? u1a u1b))
      ;; Different generators will almost certainly have different initial random seeds
      (is (not= u1a u2a))))

  (testing "Clock rollback preserves monotonicity"
    ;; This tests the internal behavior - we can't easily simulate
    ;; actual clock rollback, but we verify the algorithm handles it
    (let [u1 (uuidv7/uuidv7)
          u2 (uuidv7/uuidv7)
          u3 (uuidv7/uuidv7)]
      (is (uuid<? u1 u2))
      (is (uuid<? u2 u3)))))

(deftest test-key-extraction-consistency
  (testing "Key extraction is consistent with UUID comparison"
    (dotimes [_ 20]
      (let [u1 (uuidv7/uuidv7)
            u2 (uuidv7/uuidv7)
            k1 (uuidv7/extract-key u1)
            k2 (uuidv7/extract-key u2)]
        (is (= (uuid<? u1 u2)
               (uuid<? k1 k2))
            "Key comparison should match UUID comparison")))))

(deftest test-hex-string-format
  (testing "UUID prints in standard format"
    (let [u (uuidv7/uuidv7)
          s (str u)]
      (is (= 36 (count s)))
      (is (= 4 (count (re-seq #"-" s))))
      (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}" s)))))

(deftest test-string-sorting-order
  (testing "UUIDv7 string representations sort in generation order"
    (let [uuids (repeatedly 100 uuidv7/uuidv7)
          strings (map str uuids)]
      (is (= strings (sort strings))
          "String sort should preserve generation order"))))

#?(:clj
   (deftest test-concurrent-generation
     (testing "Shared generator produces unique UUIDs under contention"
       (let [n-threads  10
             n-per-thread 1000
             results    (mapv deref
                              (mapv (fn [_]
                                      (future
                                        (doall (repeatedly n-per-thread uuidv7/uuidv7))))
                                    (range n-threads)))
             all-uuids  (apply concat results)]
         (is (= (* n-threads n-per-thread) (count all-uuids))
             "Should generate expected number of UUIDs")
         (is (= (count all-uuids) (count (set all-uuids)))
             "All UUIDs should be unique")))

     (testing "Per-thread generators each produce monotonic sequences"
       (let [n-threads  10
             n-per-thread 1000
             results    (mapv deref
                              (mapv (fn [_]
                                      (future
                                        (let [gen (uuidv7/make-generator)]
                                          (doall (repeatedly n-per-thread gen)))))
                                    (range n-threads)))]
         (doseq [thread-uuids results]
           (is (every? true? (map uuid<? thread-uuids (rest thread-uuids)))
               "Each thread's sequence should be strictly monotonic"))))))

(defn- thrown-data
  "Call f; return the ex-data of what it throws, :no-ex-data for a throw
   without ex-data, or ::no-throw. Portable stand-in for `thrown?`, which
   the scittle clojure.test shim does not support."
  [f]
  (try (f) ::no-throw
       (catch #?(:clj Throwable :cljs :default) e
         (or (ex-data e) :no-ex-data))))

(deftest test-uuidv7?-accepts
  (testing "generated UUID objects and their strings"
    (let [u (uuidv7/uuidv7)]
      (is (true? (uuidv7/uuidv7? u)))
      (is (true? (uuidv7/uuidv7? (str u))))))

  (testing "uppercase and mixed-case strings, for every variant digit"
    (doseq [s ["0195A4C8-1234-7ABC-8BCD-0123456789AB"
               "0195A4C8-1234-7ABC-9BCD-0123456789AB"
               "0195A4C8-1234-7ABC-ABCD-0123456789AB"
               "0195A4C8-1234-7ABC-BBCD-0123456789AB"
               "0195a4c8-1234-7AbC-bBcD-0123456789aB"]]
      (is (true? (uuidv7/uuidv7? s)) s))))

(deftest test-uuidv7?-rejects
  (testing "non-v7 UUIDs"
    (is (false? (uuidv7/uuidv7? (random-uuid))))
    (is (false? (uuidv7/uuidv7? "0195a4c8-1234-4abc-8bcd-0123456789ab")))  ; version 4
    (is (false? (uuidv7/uuidv7? "0195a4c8-1234-7abc-cbcd-0123456789ab")))) ; variant 110x

  (testing "values that only look right at the version and variant positions"
    (doseq [s ["xxxxxxxxxxxxxx7xxxx8"
               "xxxxxxxx-xxxx-7xxx-8xxx-xxxxxxxxxxxx"
               "0195a4c8-1234-7abc-8bcd-0123456789ab0"  ; one hex digit too many
               "0195a4c8-1234-7abc-8bcd-0123456789a"    ; one too few
               "0195a4c812347abc8bcd0123456789ab"       ; no dashes
               "{0195a4c8-1234-7abc-8bcd-0123456789ab}"
               "urn:uuid:0195a4c8-1234-7abc-8bcd-0123456789ab"
               " 0195a4c8-1234-7abc-8bcd-0123456789ab"]]
      (is (false? (uuidv7/uuidv7? s)) s)))

  (testing "nil, empty, short and non-string values return false, never throw"
    (doseq [x [nil "" "abc" "7" 42 :k []]]
      (is (false? (uuidv7/uuidv7? x)) (pr-str x)))))

(deftest test-extraction-rejects-non-v7
  (testing "extractors throw ex-info with :type ::not-uuidv7"
    (doseq [[fname f] [["extract-ts"      uuidv7/extract-ts]
                       ["extract-counter" uuidv7/extract-counter]
                       ["extract-key"     uuidv7/extract-key]
                       ["extract-inst"    uuidv7/extract-inst]]
            bad       [(random-uuid) "xxxxxxxxxxxxxx7xxxx8" nil]]
      (is (= :com.github.franks42.uuidv7.core/not-uuidv7
             (:type (thrown-data #(f bad))))
          (str fname " " (pr-str bad))))))

(deftest test-extraction-accepts-uppercase
  (testing "an uppercase string extracts the same values as the lowercase one"
    ;; Variant digit A: the case the old check got wrong (8/9 passed by luck)
    (let [lo "0195a4c8-1234-7abc-abcd-0123456789ab"
          up "0195A4C8-1234-7ABC-ABCD-0123456789AB"]
      (is (= (uuidv7/extract-key lo) (uuidv7/extract-key up))))))

(defn- byte-seq
  "Seq over a byte[] (JVM/bb) or Uint8Array (CLJS/nbb/Scittle)."
  [bs]
  #?(:clj (seq bs) :cljs (array-seq bs)))

(deftest test-random-bytes
  (testing "random-bytes returns n bytes from the platform CSPRNG"
    (let [n  32
          bs (uuidv7/random-bytes n)]
      (is (= n (alength bs)))
      (is (= 1000 (count (set (repeatedly 1000 #(vec (byte-seq (uuidv7/random-bytes 16)))))))
          "1000 draws of 16 bytes are distinct")))
  (testing "sizes above the 65,536-byte getRandomValues limit are filled"
    (let [bs   (uuidv7/random-bytes 70000)
          tail (drop 65536 (byte-seq bs))]
      (is (= 70000 (alength bs)))
      (is (< 4000 (count (filter #(not= 0 %) tail)))
          "the bytes past the first 65,536-byte chunk are random, not zero-filled"))))

#?(:cljs
   (deftest test-no-math-random
     ;; cljs.core/random-uuid is built on Math.random (not a CSPRNG); uuidv7
     ;; used it on CLJS/nbb/Scittle until 0.7.1. With Math.random pinned to
     ;; a constant, a Math.random-based generator yields identical "random"
     ;; bits; a crypto.getRandomValues-based one does not.
     (testing "generator randomness does not come from Math.random"
       (let [orig (.-random js/Math)]
         (try
           (set! (.-random js/Math) (fn [] 0.5))
           (let [g1 (uuidv7/make-generator)
                 g2 (uuidv7/make-generator)
                 a  (str (g1))
                 b  (str (g2))]
             (is (not= (subs a 13) (subs b 13))
                 "two generators' random bits differ with Math.random pinned"))
           (finally (set! (.-random js/Math) orig)))))))

#?(:cljs
   (deftest test-fails-closed-without-crypto
     ;; With no crypto.getRandomValues, generation must throw instead of
     ;; falling back to a weaker source. Runs where globalThis.crypto is
     ;; configurable (Node, Chromium); elsewhere it only checks the guard.
     (testing "random-bytes throws when crypto.getRandomValues is missing"
       (let [d (js/Object.getOwnPropertyDescriptor js/globalThis "crypto")]
         (if (and d (.-configurable d))
           (try
             (js/Object.defineProperty js/globalThis "crypto"
                                       #js {:value js/undefined :configurable true :writable true})
             (is (= :com.github.franks42.uuidv7.core/no-secure-random
                    (try (uuidv7/random-bytes 4) :no-throw
                         (catch :default e (:type (ex-data e))))))
             (finally (js/Object.defineProperty js/globalThis "crypto" d)))
           (is (some? (uuidv7/random-bytes 4)) "crypto not configurable here; guard not exercised"))))))

;; ---------------------------------------------------------------------------
;; The pure core: next-state and state->uuid take everything as arguments
;; (clock reading and random bytes included), so they get known-answer tests.
;; ---------------------------------------------------------------------------

(def ^:private next-state #'uuidv7/next-state)
(def ^:private state->uuid #'uuidv7/state->uuid)

(defn- rnd
  "14 'random' bytes, chosen: 10 seed bytes then 4 increment bytes."
  [seed10 inc4]
  (let [xs (concat seed10 inc4)]
    #?(:clj  (byte-array (map unchecked-byte xs))
       :cljs (js/Uint8Array.from (clj->js xs)))))

(def ^:private seed-a
  "Seed bytes giving rand-a 0xABC, rand-b-hi 0x12345678 mod 2^30, rand-b-lo 0x9ABCDEF0."
  [0xFA 0xBC 0x12 0x34 0x56 0x78 0x9A 0xBC 0xDE 0xF0])

(def ^:private field-max {:a 4095 :hi 1073741823 :lo 4294967295})

(deftest test-next-state-known-answers
  (testing "new millisecond: fields come from the 10 seed bytes (mod 2^12, 2^30, 2^32)"
    (is (= {:ts 1000 :rand-a 0xABC :rand-b-hi (mod 0x12345678 1073741824) :rand-b-lo 0x9ABCDEF0}
           (next-state {:ts 999 :rand-a 1 :rand-b-hi 2 :rand-b-lo 3} 1000 (rnd seed-a [0 0 0 0])))))
  (testing "same millisecond: counter += 1 + (increment bytes mod 2^31)"
    (is (= {:ts 1000 :rand-a 1 :rand-b-hi 2 :rand-b-lo 8}
           (next-state {:ts 1000 :rand-a 1 :rand-b-hi 2 :rand-b-lo 3} 1000 (rnd seed-a [0 0 0 4])))))
  (testing "increment range: [1, 2^31]"
    (is (= 1 (:rand-b-lo (next-state {:ts 5 :rand-a 0 :rand-b-hi 0 :rand-b-lo 0} 5 (rnd seed-a [0 0 0 0])))))
    (is (= 2147483648 (:rand-b-lo (next-state {:ts 5 :rand-a 0 :rand-b-hi 0 :rand-b-lo 0} 5
                                              (rnd seed-a [0xFF 0xFF 0xFF 0xFF]))))
        "0xFFFFFFFF mod 2^31 = 2^31 - 1, plus one"))
  (testing "carry from rand-b-lo into rand-b-hi"
    (is (= {:ts 7 :rand-a 3 :rand-b-hi 11 :rand-b-lo 0}
           (next-state {:ts 7 :rand-a 3 :rand-b-hi 10 :rand-b-lo (:lo field-max)} 7 (rnd seed-a [0 0 0 0])))))
  (testing "carry through rand-b-hi into rand-a"
    (is (= {:ts 7 :rand-a 4 :rand-b-hi 0 :rand-b-lo 0}
           (next-state {:ts 7 :rand-a 3 :rand-b-hi (:hi field-max) :rand-b-lo (:lo field-max)} 7
                       (rnd seed-a [0 0 0 0])))))
  (testing "74-bit overflow: ts advances by one and the counter reseeds from the seed bytes"
    (is (= {:ts 8 :rand-a 0xABC :rand-b-hi (mod 0x12345678 1073741824) :rand-b-lo 0x9ABCDEF0}
           (next-state {:ts 7 :rand-a (:a field-max) :rand-b-hi (:hi field-max) :rand-b-lo (:lo field-max)} 7
                       (rnd seed-a [0 0 0 0])))))
  (testing "clock rollback: ts is kept and the counter still increases"
    (is (= {:ts 1000 :rand-a 1 :rand-b-hi 2 :rand-b-lo 4}
           (next-state {:ts 1000 :rand-a 1 :rand-b-hi 2 :rand-b-lo 3} 400 (rnd seed-a [0 0 0 0]))))))

(deftest test-next-state-is-pure
  (let [s0 {:ts 1000 :rand-a 1 :rand-b-hi 2 :rand-b-lo 3}
        r  (rnd seed-a [0 0 1 0])
        before (vec (byte-seq r))]
    (is (= (next-state s0 1000 r) (next-state s0 1000 r)) "same arguments, same result")
    (is (= (next-state s0 1001 r) (next-state s0 1001 r)))
    (is (= before (vec (byte-seq r))) "the random bytes are not modified")
    (testing "exactly 14 random bytes are required"
      (doseq [n [0 10 13 15]]
        (is (= :com.github.franks42.uuidv7.core/bad-random-bytes
               (try (next-state s0 1000 (rnd (repeat (min n 10) 0) (repeat (max 0 (- n 10)) 0))) :no-throw
                    (catch #?(:clj Exception :cljs :default) e (:type (ex-data e)))))
            (str n " bytes"))))))

(deftest test-next-state-is-monotonic
  (testing "from any state, for any clock reading and random bytes, the UUID increases"
    (doseq [s     [{:ts 1000 :rand-a 0 :rand-b-hi 0 :rand-b-lo 0}
                   {:ts 1000 :rand-a 4095 :rand-b-hi 1073741823 :rand-b-lo 4294967295}
                   {:ts 1000 :rand-a 2048 :rand-b-hi 5 :rand-b-lo 4294967000}]
            now   [0 999 1000 1001 5000]
            inc4  [[0 0 0 0] [0xFF 0xFF 0xFF 0xFF] [0x12 0x34 0x56 0x78]]
            seed  [seed-a (repeat 10 0) (repeat 10 0xFF)]]
      (is (uuid<? (state->uuid s) (state->uuid (next-state s now (rnd seed inc4))))
          (pr-str s now inc4 seed)))))

(deftest test-state->uuid-known-answer
  (is (= "0195a4c8-1234-7abc-9234-56789abcdef0"
         (str (state->uuid {:ts 0x0195a4c81234 :rand-a 0xABC
                            :rand-b-hi (mod 0x12345678 1073741824) :rand-b-lo 0x9ABCDEF0}))))
  (is (uuidv7/uuidv7? (state->uuid {:ts 0 :rand-a 0 :rand-b-hi 0 :rand-b-lo 0}))))
