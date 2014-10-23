(ns congestion.middleware-test
  (:require [clojure.test :refer :all]
            [congestion.middleware :refer :all]
            [ring.mock.request :as mock]))

(def response
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, world!"})

(def stacked-response
  {:status 200
   :headers {"Content-Type" "text/plain"
             "X-RateLimit-Limit" "999"
             "X-RateLimit-Remaining" "900"}
   :body "Hello, world!"
   :congestion.middleware/rate-limit-applied true})

(def exhausted-stacked-response
  {:status 429
   :headers {"Content-Type" "text/plain"
             "Retry-After" "tomorrow"
             "X-RateLimit-Limit" "999"
             "X-RateLimit-Remaining" "0"}
   :body "Too many requests"
   :congestion.middleware/rate-limit-applied true})

(defn rate-limit [rsp] (get-in rsp [:headers "X-RateLimit-Limit"]))
(defn remaining [rsp] (get-in rsp [:headers "X-RateLimit-Remaining"]))
(defn retry-after [rsp] (get-in rsp [:headers "Retry-After"]))

(deftest test-wrap-rate-limit
  (testing "single wrap-rate-limit"
    (testing "with unexhausted limit"
      (let [counter-state {:rate-limit-exhausted? false
                           :remaining-requests 1
                           :quota 10}
            handler (-> response
                        constantly
                        (wrap-rate-limit :mock-storage :mock-limit))]
        (with-redefs [read-counter (fn [storage limit req]
                                     (is (= storage :mock-storage))
                                     (is (= limit :mock-limit))
                                     (is (= req :mock-req))
                                     counter-state)
                      increment-counter (fn [storage limit req]
                                          (is (= storage :mock-storage))
                                          (is (= limit :mock-limit)))]
          (let [rsp (handler :mock-req)]
            (is (= (:status rsp) 200))
            (is (= (rate-limit rsp) "10"))
            (is (= (remaining rsp) "1"))))))

    (testing "with exhausted limit"
      (let [counter-state {:rate-limit-exhausted? true
                           :remaining-requests 0
                           :quota 10
                           :retry-after "next week"}
            handler (-> response
                        constantly
                        (wrap-rate-limit :mock-storage :mock-limit))]
        (with-redefs [read-counter (fn [storage limit req]
                                     (is (= storage :mock-storage))
                                     (is (= limit :mock-limit))
                                     (is (= req :mock-req))
                                     counter-state)
                      increment-counter (fn [_ _ _]
                                          (is false "should not be called"))]
          (let [rsp (handler :mock-req)]
            (is (= (:status rsp) 429))
            (is (= (retry-after rsp) "next week"))
            (is (= (rate-limit rsp) "10"))
            (is (= (remaining rsp) "0")))))))

  (testing "stacked wrap-rate-limit middlewares"
    (testing "with second limit applied"
      (let [counter-state {:rate-limit-exhausted? false
                           :remaining-requests 1
                           :quota 10}
            handler (-> stacked-response
                        constantly
                        (wrap-rate-limit :mock-storage :mock-limit))]
        (with-redefs [read-counter (fn [storage limit req]
                                     (is (= storage :mock-storage))
                                     (is (= limit :mock-limit))
                                     (is (= req :mock-req))
                                     counter-state)
                      increment-counter (fn [_ _ _]
                                          (is false "should not be called"))]
          (let [rsp (handler :mock-req)]
            (is (= (:status rsp) 200))
            (is (= (rate-limit rsp) "999"))
            (is (= (remaining rsp) "900"))))))

    (testing "with exhausted second rate limit"
      (let [counter-state {:rate-limit-exhausted? false
                           :remaining-requests 1
                           :quota 10}
            handler (-> exhausted-stacked-response
                        constantly
                        (wrap-rate-limit :mock-storage :mock-limit))]
        (with-redefs [read-counter (fn [storage limit req]
                                     (is (= storage :mock-storage))
                                     (is (= limit :mock-limit))
                                     (is (= req :mock-req))
                                     counter-state)
                      increment-counter (fn [_ _ _]
                                          (is false "should not be called"))]
          (let [rsp (handler :mock-req)]
            (is (= (:status rsp) 429))
            (is (= (retry-after rsp) "tomorrow"))
            (is (= (rate-limit rsp) "999"))
            (is (= (remaining rsp) "0"))))))

    (testing "with exhausted first rate limit"
      (let [counter-state {:rate-limit-exhausted? true
                           :remaining-requests 0
                           :quota 10
                           :retry-after "next week"}
            handler (-> exhausted-stacked-response
                        constantly
                        (wrap-rate-limit :mock-storage :mock-limit))]
        (with-redefs [read-counter (fn [storage limit req]
                                     (is (= storage :mock-storage))
                                     (is (= limit :mock-limit))
                                     (is (= req :mock-req))
                                     counter-state)
                      increment-counter (fn [_ _ _]
                                          (is false "should not be called"))]
          (let [rsp (handler :mock-req)]
            (is (= (:status rsp) 429))
            (is (= (retry-after rsp) "next week"))
            (is (= (rate-limit rsp) "10"))
            (is (= (remaining rsp) "0"))))))))
