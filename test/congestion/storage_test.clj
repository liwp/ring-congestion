(ns congestion.storage-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [congestion.storage :refer :all]))

(def ^:dynamic *storage* nil)

(defn with-storage
  [f]
  (binding [*storage* (->LocalStorage (atom {}))]
    (f)
    (clear-counters *storage*)))

(use-fixtures :each with-storage)

(deftest ^:redis test-increments-counters
  (doseq [k [:increments-counters-b
             :increments-counters-a
             :increments-counters-a
             :increments-counters-b
             :increments-counters-b
             :increments-counters-c]]
    (increment-count *storage* k (t/seconds 10)))

  (is (= (get-count *storage* :increments-counters-a) 2))
  (is (= (get-count *storage* :increments-counters-b) 3))
  (is (= (get-count *storage* :increments-counters-c) 1))
  (is (= (get-count *storage* :increments-counters-d) 0)))

(deftest ^:redis test-clears-counters
  (increment-count *storage* :clears-counters-a (t/seconds 10))
  (increment-count *storage* :clears-counters-b (t/minutes 10))

  (clear-counters *storage*)
  (is (= (get-count *storage* :clears-counters-a) 0))
  (is (= (get-count *storage* :clears-counters-b) 0)))

(deftest ^:redis test-expires-counters
  (increment-count *storage* :expiring-counters-a (t/seconds 1))
  (increment-count *storage* :expiring-counters-b (t/minutes 10))

  (Thread/sleep 1100)

  (is (= (get-count *storage* :expiring-counters-a) 0))
  (is (= (get-count *storage* :expiring-counters-b) 1)))

(deftest ^:redis test-returns-expiration-time
  (let [now (t/now)]
    (with-redefs [t/now (fn [] now)]
      (increment-count *storage* :expiration-time-a (t/seconds 10))
      (increment-count *storage* :expiration-time-b (t/minutes 10)))

    (is (not (t/after? (t/plus now (t/seconds 10))
                       (counter-expiry *storage* :expiration-time-a))))
    (is (not (t/after? (t/plus now (t/minutes 10))
                       (counter-expiry *storage* :expiration-time-b))))))
