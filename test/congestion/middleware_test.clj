(ns congestion.middleware-test
  (:require [clj-time.coerce :as c]
            [clojure.test :refer :all]
            [congestion.limits :as l]
            [congestion.middleware :refer :all]
            [congestion.storage :as s]))

(def response
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, world!"})

(defrecord MockStorage [counters timeouts]
  s/Storage
  (get-count [self key]
    (get @counters key 0))
  (increment-count [self key timeout]
    (swap! counters update-in [key] (fnil inc 0))
    (swap! timeouts assoc key timeout))
  (counter-expiry [self key]
    (get @timeouts key)))

(defrecord MockRateLimit [quota key period]
  l/RateLimit
  (get-quota [self]
    quota)
  (get-key [self req]
    key)
  (get-period [self]
    period))

(defn rate-limit [rsp] (get-in rsp [:headers "X-RateLimit-Limit"]))
(defn remaining [rsp] (get-in rsp [:headers "X-RateLimit-Remaining"]))
(defn retry-after [rsp] (get-in rsp [:headers "Retry-After"]))

(deftest ^:unit test-wrap-rate-limit
  (testing "single wrap-rate-limit"
    (testing "with unexhausted quota"
      (let [storage (->MockStorage (atom {}) (atom {}))
            limit (->MockRateLimit 10 :mock-limit-key :mock-ttl)
            handler (-> response
                        constantly
                        (wrap-rate-limit storage limit))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 200))
          (is (= (:body "Hello, world!")))
          (is (= (get-in rsp [:headers "Content-Type"] "text/plain")))
          (is (= (rate-limit rsp) "10"))
          (is (= (remaining rsp) "9"))
          (is (= (s/get-count storage :mock-limit-key) 1))
          (is (= (s/counter-expiry storage :mock-limit-key) :mock-ttl)))))

    (testing "with exhausted limit"
      (let [counter-expiry (c/from-date #inst "2014-12-31T12:34:56Z")
            storage (->MockStorage (atom {:mock-limit-key 10})
                                   (atom {:mock-limit-key counter-expiry}))
            limit (->MockRateLimit 10 :mock-limit-key :mock-ttl)
            handler (-> response
                        constantly
                        (wrap-rate-limit storage limit))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 429))
          (is (= (rate-limit rsp) "10"))
          (is (= (remaining rsp) "0"))
          (is (= (retry-after rsp) "Wed, 31 Dec 2014 12:34:56 GMT"))
          (is (= (s/get-count storage :mock-limit-key) 10))))))

  (testing "stacked wrap-rate-limit middlewares"
    (testing "with second limit applied"
      (let [storage (->MockStorage (atom {}) (atom {}))
            first-limit (->MockRateLimit 1000 :first-limit-key :first-ttl)
            second-limit (->MockRateLimit 10 :second-limit-key :second-ttl)
            handler (-> response
                        constantly
                        (wrap-rate-limit storage first-limit)
                        (wrap-rate-limit storage second-limit))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 200))
          (is (= (rate-limit rsp) "1000"))
          (is (= (remaining rsp) "999"))
          (is (= (s/get-count storage :first-limit-key) 1))
          (is (= (s/counter-expiry storage :first-limit-key) :first-ttl)))))

    (testing "with exhausted first rate limit"
      (let [counter-expiry (c/from-date #inst "2014-12-31T12:34:56Z")
            storage (->MockStorage (atom {:first-limit-key 1000})
                                   (atom {:first-limit-key counter-expiry}))
            first-limit (->MockRateLimit 1000 :first-limit-key :first-ttl)
            second-limit (->MockRateLimit 10 :second-limit-key :second-ttl)
            handler (-> response
                        constantly
                        (wrap-rate-limit storage first-limit)
                        (wrap-rate-limit storage second-limit))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 429))
          (is (= (retry-after rsp) "Wed, 31 Dec 2014 12:34:56 GMT"))
          (is (= (rate-limit rsp) "1000"))
          (is (= (remaining rsp) "0"))
          (is (= (s/get-count storage :first-limit-key) 1000))
          (is (= (s/get-count storage :second-limit-key) 0)))))

    (testing "with exhausted second rate limit"
      (let [counter-expiry (c/from-date #inst "2014-12-31T12:34:56Z")
            storage (->MockStorage (atom {:second-limit-key 10})
                                   (atom {:second-limit-key counter-expiry}))
            first-limit (->MockRateLimit 1000 :first-limit-key :first-ttl)
            second-limit (->MockRateLimit 10 :second-limit-key :second-ttl)
            handler (-> response
                        constantly
                        (wrap-rate-limit storage first-limit)
                        (wrap-rate-limit storage second-limit))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 429))
          (is (= (rate-limit rsp) "10"))
          (is (= (remaining rsp) "0"))
          (is (= (retry-after rsp) "Wed, 31 Dec 2014 12:34:56 GMT"))
          (is (= (s/get-count storage :first-limit-key) 0))
          (is (= (s/get-count storage :second-limit-key) 10)))))))
