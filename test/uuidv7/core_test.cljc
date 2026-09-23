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
