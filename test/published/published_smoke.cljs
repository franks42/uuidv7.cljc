(ns published-smoke
  "Smoke test for a *published* uuidv7 (jsdelivr CDN or nbb git dep).
   Uses only API present since 0.6.0, so it can run against any release,
   unlike uuidv7.core-test, which tracks main. Throws on the first failed
   check; on success prints `SMOKE OK <version>`."
  (:require [com.github.franks42.uuidv7.core :as uuidv7]))

(defn- check [ok what]
  (when-not ok
    (throw (js/Error. (str "SMOKE FAIL: " what)))))

(let [us  (vec (repeatedly 100 uuidv7/uuidv7))
      u   (first us)
      now (js/Date.now)]
  (check (every? uuid? us) "uuidv7 returns UUIDs")
  (check (every? uuidv7/uuidv7? us) "generated UUIDs are v7")
  (check (= (map str us) (sort (map str us))) "strings sort in generation order")
  (check (apply distinct? us) "UUIDs are distinct")
  (check (<= (- now 5000) (uuidv7/extract-ts u) now) "extract-ts is about now")
  (check (= 4 (count (uuidv7/extract-key u))) "extract-key has four parts")
  (check (not (uuidv7/uuidv7? (random-uuid))) "a v4 UUID is not v7")
  (check (string? uuidv7/version) "version is a string"))

(println (str "SMOKE OK " uuidv7/version))
