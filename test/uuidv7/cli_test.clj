(ns uuidv7.cli-test
  "Integration tests for bin/uuidv7 (the UUIDv7 CLI).

  Each test shells out to the script and asserts on stdout, stderr,
  and exit code. The script must be executable and present at
  bin/uuidv7 relative to the working directory (the repo root when
  invoked via `bb test:cli`)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.edn :as edn]
            [com.github.franks42.uuidv7.core :as uuidv7]))

(def cli-bin "bin/uuidv7")

(defn run
  "Invoke the CLI with the given args and optional stdin. Returns
  {:exit, :out, :err}."
  ([args]            (run args nil))
  ([args stdin]
   (apply shell/sh
          (concat [cli-bin] args
                  (when stdin [:in stdin])))))

;; ----- top-level surface -----

(deftest version-flag
  (let [{:keys [exit out]} (run ["--version"])]
    (is (= 0 exit))
    (is (str/starts-with? out "uuidv7 "))))

(deftest help-flag
  (let [{:keys [exit out]} (run ["--help"])]
    (is (= 0 exit))
    (is (str/includes? out "USAGE"))
    (is (str/includes? out "gen"))
    (is (str/includes? out "parse"))
    (is (str/includes? out "valid"))))

(deftest no-args-prints-help
  (let [{:keys [exit out]} (run [])]
    (is (= 0 exit))
    (is (str/includes? out "uuidv7"))))

(deftest unknown-subcommand-exits-2
  (let [{:keys [exit err]} (run ["frobnicate"])]
    (is (= 2 exit))
    (is (str/includes? err "unknown subcommand"))))

;; ----- gen -----

(deftest gen-default-format
  (let [{:keys [exit out]} (run ["gen"])
        line (str/trim out)]
    (is (= 0 exit))
    (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}" line)
        "matches UUIDv7 hex shape with v7 nibble at position 13")))

(deftest gen-format-urn
  (let [{:keys [exit out]} (run ["gen" "--format" "urn"])
        line (str/trim out)]
    (is (= 0 exit))
    (is (str/starts-with? line "urn:uuid:"))
    (is (re-matches #"urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}" line))))

(deftest gen-format-edn
  (let [{:keys [exit out]} (run ["gen" "--format" "edn"])
        line (str/trim out)
        record (edn/read-string line)]
    (is (= 0 exit))
    (is (uuid? (:uuid record)))
    (is (str/starts-with? (:uri record) "urn:uuid:"))
    (is (instance? java.util.Date (:datetime record)))
    (is (vector? (:counter record)))
    (is (= 3 (count (:counter record))))))

(deftest gen-output-file
  (let [out-path (str (System/getProperty "java.io.tmpdir") "/uuidv7-cli-test.txt")]
    (try
      (let [{:keys [exit]} (run ["gen" "--output" out-path])]
        (is (= 0 exit))
        (let [line (str/trim (slurp out-path))]
          (is (re-matches #"[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[0-9a-f]{4}-[0-9a-f]{12}" line))))
      (finally
        (.delete (java.io.File. out-path))))))

(deftest gen-uuids-are-uuidv7s
  (testing "successive gen calls produce different UUIDs"
    (let [u1 (str/trim (:out (run ["gen"])))
          u2 (str/trim (:out (run ["gen"])))]
      (is (not= u1 u2)))))

;; ----- parse -----

(deftest parse-positional-default-edn
  (let [u (str/trim (:out (run ["gen"])))
        {:keys [exit out]} (run ["parse" u])
        record (edn/read-string (str/trim out))]
    (is (= 0 exit))
    (is (= (java.util.UUID/fromString u) (:uuid record)))
    (is (= (str "urn:uuid:" u) (:uri record)))))

(deftest parse-positional-format-uuid
  (testing "parse --format uuid is essentially identity for valid v7 input"
    (let [u (str/trim (:out (run ["gen"])))
          {:keys [exit out]} (run ["parse" u "--format" "uuid"])]
      (is (= 0 exit))
      (is (= u (str/trim out))))))

(deftest parse-positional-format-urn
  (let [u (str/trim (:out (run ["gen"])))
        {:keys [exit out]} (run ["parse" u "--format" "urn"])]
    (is (= 0 exit))
    (is (= (str "urn:uuid:" u) (str/trim out)))))

(deftest parse-positional-after-flags
  (testing "positional UUID can appear after flags"
    (let [u (str/trim (:out (run ["gen"])))
          {:keys [exit out]} (run ["parse" "--format" "urn" u])]
      (is (= 0 exit))
      (is (= (str "urn:uuid:" u) (str/trim out))))))

(deftest parse-stdin
  (let [u (str/trim (:out (run ["gen"])))
        {:keys [exit out]} (run ["parse" "--format" "uuid"] (str u "\n"))]
    (is (= 0 exit))
    (is (= u (str/trim out)))))

(deftest parse-stdin-multiple
  (let [u1 (str/trim (:out (run ["gen"])))
        u2 (str/trim (:out (run ["gen"])))
        u3 (str/trim (:out (run ["gen"])))
        {:keys [exit out]} (run ["parse" "--format" "uuid"]
                                (str u1 "\n" u2 "\n" u3 "\n"))]
    (is (= 0 exit))
    (is (= [u1 u2 u3] (str/split-lines (str/trim out))))))

(deftest parse-non-v7-uuid-exits-1
  (testing "v4 UUID is rejected"
    (let [{:keys [exit err]} (run ["parse" "550e8400-e29b-41d4-a716-446655440000"])]
      (is (= 1 exit))
      (is (str/includes? err "not a UUIDv7")))))

(deftest parse-malformed-uuid-exits-1
  (let [{:keys [exit err]} (run ["parse" "not-a-uuid"])]
    (is (= 1 exit))
    (is (str/includes? err "malformed UUID"))))

(deftest parse-positional-and-input-exits-2
  (let [{:keys [exit err]} (run ["parse" "0195-fake" "--input" "/tmp/whatever"])]
    (is (= 2 exit))
    (is (str/includes? err "mutually exclusive"))))

;; ----- valid -----

(deftest valid-positive
  (let [u (str/trim (:out (run ["gen"])))
        {:keys [exit out]} (run ["valid" u])]
    (is (= 0 exit))
    (is (str/blank? out)
        "valid emits no stdout — it's pure exit-code predicate")))

(deftest valid-rejects-v4
  (let [{:keys [exit err]} (run ["valid" "550e8400-e29b-41d4-a716-446655440000"])]
    (is (= 1 exit))
    (is (str/includes? err "not a UUIDv7"))))

(deftest valid-rejects-malformed
  (let [{:keys [exit err]} (run ["valid" "not-a-uuid"])]
    (is (= 1 exit))
    (is (str/includes? err "malformed UUID"))))

(deftest valid-stdin-multiple-all-pass
  (let [u1 (str/trim (:out (run ["gen"])))
        u2 (str/trim (:out (run ["gen"])))
        u3 (str/trim (:out (run ["gen"])))
        {:keys [exit]} (run ["valid"] (str u1 "\n" u2 "\n" u3 "\n"))]
    (is (= 0 exit))))

(deftest valid-fail-fast-on-first-bad
  (let [u-good (str/trim (:out (run ["gen"])))
        u-bad  "550e8400-e29b-41d4-a716-446655440000"
        {:keys [exit err]} (run ["valid"] (str u-good "\n" u-bad "\n"))]
    (is (= 1 exit))
    (is (str/includes? err "not a UUIDv7"))))

;; ----- round-trip via library -----

(deftest gen-then-parse-roundtrip
  (testing "library and CLI agree on extraction"
    (let [u-str (str/trim (:out (run ["gen"])))
          u     (java.util.UUID/fromString u-str)
          {:keys [out]} (run ["parse" u-str])
          record (edn/read-string (str/trim out))]
      (is (= u (:uuid record)))
      (is (= (uuidv7/extract-inst u) (:datetime record)))
      (is (= (vec (uuidv7/extract-counter u)) (:counter record))))))
