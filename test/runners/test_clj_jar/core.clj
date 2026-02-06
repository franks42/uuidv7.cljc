(ns test-clj-jar.core
  "Test runner for the JAR-based tests on Clojure/JVM"
  (:require [clojure.java.io :as io]
            [clojure.test :refer [run-tests]]))

(defn -main [_]
  (let [jar-path "target/uuidv7.jar"]
    (if (.exists (io/file jar-path))
      (do
        (println "Running tests against JAR:" jar-path)
        (load-file "test/uuidv7/core_test.cljc")
        (run-tests 'uuidv7.core-test))
      (do
        (println "ERROR: JAR not found at" jar-path)
        (println "Run 'clojure -T:build jar' first to build the JAR.")
        (System/exit 1)))))
