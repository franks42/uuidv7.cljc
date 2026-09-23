(ns test-cljs.core
  "Test runner for compiled ClojureScript (Node.js target)"
  (:require [cljs.test :as t]
            [uuidv7.core-test]))

;; cljs.test/run-tests does not return the summary map, so the exit code
;; has to come from the :end-run-tests report event.
(defmethod t/report [::t/default :end-run-tests] [m]
  (when-not (t/successful? m)
    (js/process.exit 1)))

(defn -main []
  (t/run-tests 'uuidv7.core-test))

(-main)
