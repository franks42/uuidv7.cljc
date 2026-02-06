(ns clojure.test)

(def ^:dynamic *tests* (atom []))
(def ^:dynamic *results* (atom {:pass 0 :fail 0 :error 0}))
(def ^:dynamic *output* (atom []))
(def ^:dynamic *current-testing* (atom nil))

(defn- log [& args]
  (swap! *output* conj (apply str args)))

(defmacro testing [desc & body]
  `(do
     (reset! *current-testing* ~desc)
     ~@body
     (reset! *current-testing* nil)))

(defmacro is [form]
  `(let [result# (try
                   (let [v# ~form]
                     (if v#
                       (do (swap! *results* update :pass inc) true)
                       (do (swap! *results* update :fail inc)
                           (log "  FAIL: " (when @*current-testing* (str "(" @*current-testing* ") "))
                                '~form)
                           false)))
                   (catch js/Error e#
                     (swap! *results* update :error inc)
                     (log "  ERROR: " (when @*current-testing* (str "(" @*current-testing* ") "))
                          '~form " \u2014 " (.-message e#))
                     false))]
     result#))

(defmacro deftest [name & body]
  `(swap! *tests* conj {:name '~name :fn (fn [] ~@body)}))

(defn run-tests [& _ns-syms]
  (doseq [{:keys [name fn]} @*tests*]
    (log "Testing " name)
    (try
      (fn)
      (catch js/Error e
        (swap! *results* update :error inc)
        (log "  ERROR in " name ": " (.-message e)))))
  (let [{:keys [pass fail error]} @*results*
        total (+ pass fail error)]
    (log "")
    (log "Ran " (count @*tests*) " tests containing " total " assertions.")
    (log (str pass " passed, " fail " failed, " error " errors."))
    @*results*))
