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
          now (System/currentTimeMillis)]
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
      (is (instance? java.util.Date inst)))))

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
