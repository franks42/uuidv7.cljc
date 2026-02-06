(ns test-nbb.core
  "Test runner for nbb (Node.js Babashka - ClojureScript)"
  (:require [clojure.test :refer [run-tests]]
            [uuidv7.core-test]))

(defn -main []
  (let [{:keys [fail error]} (run-tests 'uuidv7.core-test)]
    (when (pos? (+ fail error))
      (js/process.exit 1))))

(-main)
