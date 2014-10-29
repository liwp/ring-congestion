(ns congestion.middleware-test
  (:require [clj-time.coerce :as c]
            [clojure.test :refer :all]
            [congestion.limits :as l]
            [congestion.middleware :refer :all]
            [congestion.storage :as s]
            [congestion.test-utils :refer :all]))

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
  (get-quota [self req]
    quota)
  (get-key [self req]
    key)
  (get-period [self req]
    period))

(def ^:dynamic *storage* nil)

(defn set-counter
  [key new-value new-timeout]
  (swap! (:counters *storage*) assoc key new-value)
  (swap! (:timeouts *storage*) assoc key new-timeout))

(defmacro with-counters
  [counters & body]
  `(binding [*storage* (->MockStorage (atom {}) (atom {}))]
     (doseq [[k# v# e#] ~counters]
       (set-counter k# v# (c/from-date e#)))
     ~@body))

(defn make-request
  "Wrap `default-response` in the provided a middleware and make a
  `:mock-request`."
  [middleware limit & [response-builder]]
  (let [handler (-> default-response
                    constantly
                    (middleware {:storage *storage*
                                 :limit limit
                                 :response-builder response-builder}))]
    (handler :mock-request)))

(deftest ^:unit test-single-wrap-stacking-rate-limit-instance
  (testing "with unexhausted quota"
    (with-counters []
      (let [limit (->MockRateLimit 10 :mock-limit-key :mock-ttl)
            rsp (make-request wrap-stacking-rate-limit limit)]
        (is (= (:status rsp) 200))
        (is (= (:body "Hello, world!")))
        (is (= (get-in rsp [:headers "Content-Type"] "text/plain")))
        (is (= (rate-limit rsp) 10))
        (is (= (remaining rsp) 9))
        (is (= (s/get-count *storage* :mock-limit-key) 1))
        (is (= (s/counter-expiry *storage* :mock-limit-key) :mock-ttl)))))

  (testing "with exhausted limit"
    (with-counters [[:mock-limit-key 10 #inst "2014-12-31T12:34:56Z"]]
      (let [limit (->MockRateLimit 10 :mock-limit-key :mock-ttl)
            rsp (make-request wrap-stacking-rate-limit limit)]
        (is (= (:status rsp) 429))
        (is (= (rate-limit rsp) 10))
        (is (= (remaining rsp) 0))
        (is (= (retry-after rsp) "Wed, 31 Dec 2014 12:34:56 GMT")))))

  (testing "with custom 429 reponse"
    (with-counters [[:mock-limit-key 10 #inst "2014-12-31T12:34:56Z"]]
      (let [limit (->MockRateLimit 10 :mock-limit-key :mock-ttl)
            custom-response-handler (fn [counter-state]
                                      {:status 418
                                       :headers {"Content-Type" "text/plain"}
                                       :body "I'm a teapot"})
            rsp (make-request wrap-stacking-rate-limit
                              limit custom-response-handler)]
        (is (= rsp {:status 418
                    :headers {"Content-Type" "text/plain"}
                    :body "I'm a teapot"}))))))

(deftest ^:unit test-multiple-wrap-stacking-rate-limit-instances
  (testing "with second limit applied"
    (with-counters []
      (let [first-limit (->MockRateLimit 1000 :first-limit-key :first-ttl)
            second-limit (->MockRateLimit 10 :second-limit-key :second-ttl)
            handler (-> default-response
                        constantly
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit first-limit})
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit second-limit}))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 200))
          (is (= (rate-limit rsp) 1000))
          (is (= (remaining rsp) 999))
          (is (= (s/get-count *storage* :first-limit-key) 1))
          (is (= (s/counter-expiry *storage* :first-limit-key) :first-ttl))))))

  (testing "with exhausted first rate limit"
    (with-counters [[:first-limit-key 1000 #inst "2014-12-31T12:34:56Z"]]
      (let [first-limit (->MockRateLimit 1000 :first-limit-key :first-ttl)
            second-limit (->MockRateLimit 10 :second-limit-key :second-ttl)
            handler (-> default-response
                        constantly
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit second-limit})
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit first-limit}))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 429))
          (is (= (retry-after rsp) "Wed, 31 Dec 2014 12:34:56 GMT"))
          (is (= (rate-limit rsp) 1000))
          (is (= (remaining rsp) 0))
          (is (= (s/get-count *storage* :first-limit-key) 1000))
          (is (= (s/get-count *storage* :second-limit-key) 0))))))

  (testing "with exhausted second rate limit"
    (with-counters [[:second-limit-key 10 #inst "2014-12-31T12:34:56Z"]]
      (let [first-limit (->MockRateLimit 1000 :first-limit-key :first-ttl)
            second-limit (->MockRateLimit 10 :second-limit-key :second-ttl)
            handler (-> default-response
                        constantly
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit second-limit})
                        (wrap-stacking-rate-limit {:storage *storage*
                                                   :limit first-limit}))]
        (let [rsp (handler :mock-req)]
          (is (= (:status rsp) 429))
          (is (= (rate-limit rsp) 10))
          (is (= (remaining rsp) 0))
          (is (= (retry-after rsp) "Wed, 31 Dec 2014 12:34:56 GMT"))
          (is (= (s/get-count *storage* :first-limit-key) 0))
          (is (= (s/get-count *storage* :second-limit-key) 10)))))))
