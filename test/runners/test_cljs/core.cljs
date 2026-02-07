(ns test-cljs.core
  "Test runner for compiled ClojureScript (Node.js target)"
  (:require [clojure.test :refer [run-tests]]
            [uuidv7.core-test]))

(defn -main []
  (let [{:keys [fail error]} (run-tests 'uuidv7.core-test)]
    (when (pos? (+ fail error))
      (js/process.exit 1))))

(-main)
