(ns congestion.limits-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [congestion.limits :refer :all]))

(deftest ^:unit test-ip-rate-limit
  (testing "IpRateLimit"
    (let [limit (->IpRateLimit (t/seconds 10) 100)
          req {:remote-addr "127.0.0.1"}]
      (is (= (get-quota limit req) 100))
      (is (= (get-key limit req) ":congestion.limits/ip-127.0.0.1"))
      (is (= (get-period limit req) (t/seconds 10))))))
