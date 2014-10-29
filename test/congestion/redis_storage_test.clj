(ns congestion.redis-storage-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [congestion.redis-storage :refer :all]
            [congestion.storage :as s]))

(def ^:dynamic *storage* nil)

(defn with-storage
  [f]
  (binding [*storage* (->RedisStorage {:spec {:host "localhost" :port 6379}})]
    (f)
    (s/clear-counters *storage*)))

(use-fixtures :each with-storage)

(deftest ^:redis test-increments-counters
  (doseq [k [:increments-counters-b
             :increments-counters-a
             :increments-counters-a
             :increments-counters-b
             :increments-counters-b
             :increments-counters-c]]
    (s/increment-count *storage* k (t/seconds 10)))

  (is (= (s/get-count *storage* :increments-counters-a) 2))
  (is (= (s/get-count *storage* :increments-counters-b) 3))
  (is (= (s/get-count *storage* :increments-counters-c) 1))
  (is (= (s/get-count *storage* :increments-counters-d) 0)))

(deftest ^:redis test-clears-counters
  (s/increment-count *storage* :clears-counters-a (t/seconds 10))
  (s/increment-count *storage* :clears-counters-b (t/minutes 10))

  (s/clear-counters *storage*)
  (is (= (s/get-count *storage* :clears-counters-a) 0))
  (is (= (s/get-count *storage* :clears-counters-b) 0)))

(deftest ^:redis test-expires-counters
  (s/increment-count *storage* :expiring-counters-a (t/seconds 1))
  (s/increment-count *storage* :expiring-counters-b (t/minutes 10))

  (Thread/sleep 1100)

  (is (= (s/get-count *storage* :expiring-counters-a) 0))
  (is (= (s/get-count *storage* :expiring-counters-b) 1)))

(deftest ^:redis test-returns-expiration-time
  (let [now (t/now)]
    (with-redefs [t/now (fn [] now)]
      (s/increment-count *storage* :expiration-time-a (t/seconds 10))
      (s/increment-count *storage* :expiration-time-b (t/minutes 10)))

    (is (not (t/after? (t/plus now (t/seconds 10))
                       (s/counter-expiry *storage* :expiration-time-a))))
    (is (not (t/after? (t/plus now (t/minutes 10))
                       (s/counter-expiry *storage* :expiration-time-b))))))
