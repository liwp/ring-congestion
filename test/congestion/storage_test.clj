(ns congestion.storage-test
  (:require [clj-time.core :as t]
            [congestion.storage :refer :all]
            [clojure.test :refer :all]))

(deftest ^:unit test-local-storage
  (testing "local-storage"
    (let [state (atom {})
          storage (->LocalStorage state)
          now (t/now)]

      (testing "is initially empty"
        (is (empty? (:counters @state)))
        (is (empty? (:timeouts @state))))

      (testing "increments counters"
        (with-redefs [t/now (fn [] now)]
          (doseq [k [:increments-counters-b
                     :increments-counters-a
                     :increments-counters-a
                     :increments-counters-b
                     :increments-counters-b]]
            (increment-count storage k (t/seconds 0))))
        (is (= (:counters @state) {:increments-counters-a 2
                                   :increments-counters-b 3}))
        (is (= (:timeouts @state) {:increments-counters-a now
                                   :increments-counters-b now})))

      (testing "expires counters"
        (with-redefs [t/now (fn [] now)]
          (increment-count storage :expiring-counters-a (t/seconds 10))
          (increment-count storage :expiring-counters-b (t/minutes 10)))

        (with-redefs [t/now (fn [] (t/plus now (t/seconds 11)))]
          (is (= (get-count storage :expiring-counters-a) 0))
          (is (= (get-count storage :expiring-counters-b) 1))
          (is (= (:counters @state)
                 {:expiring-counters-b 1}))
          (is (= (:timeouts @state)
                 {:expiring-counters-b (t/plus now (t/minutes 10))}))))

      (testing "returns expiration times"
        (with-redefs [t/now (fn [] now)]
          (increment-count storage :expiration-time-a (t/seconds 10))
          (increment-count storage :expiration-time-b (t/minutes 10)))

        (is (= (counter-expiry storage :expiration-time-a)
               (t/plus now (t/seconds 10))))
        (is (= (counter-expiry storage :expiration-time-b)
               (t/plus now (t/minutes 10))))))))
