(ns congestion.acceptance-test
  (:require [clj-time.core :as t]
            [clojure.test :refer :all]
            [congestion.middleware :refer :all]
            [congestion.storage :as s]
            [congestion.redis-storage :as redis]
            [congestion.test-utils :refer :all]
            [ring.mock.request :as mock]))

(def default-response-handler
  (constantly default-response))

(defn custom-response-builder
  [counter-state]
  (too-many-requests-response
   counter-state
   {:status 418
    :headers {"Content-Type" "text/plain"}
    :body "I'm a teapot"}))

(defn create-storage
  [factory-fn]
  (let [storage (factory-fn)]
    (s/clear-counters storage)
    storage))

(deftest ^:redis test-unstacked-rate-limit
  (doseq [storage-factory-fn [s/local-storage
                              #(redis/redis-storage {:spec {:host "localhost"
                                                            :port 6379}})]]
    (testing "with available quota"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 10) 10)
            config {:storage storage
                    :limit limit}
            handler (wrap-rate-limit default-response-handler config)
            rsp (handler (mock/request :get "/"))]

        (is (= (:status rsp) 200))
        (is (= (rate-limit rsp) 10))
        (is (= (remaining rsp) 9))))

    (testing "with exhausted quota"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 10) 1)
            config {:storage storage
                    :limit limit}
            handler (wrap-rate-limit default-response-handler config)
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))
        (is (= (rate-limit rsp-b) 1))
        (is (= (remaining rsp-b) 0))
        (is (some? (retry-after rsp-b)))))

    (testing "with replenished quota"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 1) 1)
            config {:storage storage
                    :limit limit}
            handler (wrap-rate-limit default-response-handler config)
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))

        ;; Allow the counter to expire
        (Thread/sleep 1100)

        (let [rsp-c (handler (mock/request :get "/"))]
          (is (= (:status rsp-a) 200)))))

    (testing "with custom response"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 10) 1)
            config {:storage storage
                    :limit limit
                    :response-builder custom-response-builder}
            handler (wrap-rate-limit default-response-handler config)
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))

        (is (= (:status rsp-b) 418))
        (is (= (rate-limit rsp-b) 1))
        (is (= (remaining rsp-b) 0))
        (is (some? (retry-after rsp-b)))))))

(deftest ^:redis test-single-stacked-rate-limit
  (doseq [storage-factory-fn [s/local-storage
                              #(redis/redis-storage {:spec {:host "localhost"
                                                            :port 6379}})]]
    (testing "with available quota"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 10) 10)
            config {:storage storage
                    :limit limit}
            handler (wrap-stacking-rate-limit default-response-handler config)
            rsp (handler (mock/request :get "/"))]

        (is (= (:status rsp) 200))
        (is (= (rate-limit rsp) 10))
        (is (= (remaining rsp) 9))))

    (testing "with exhausted quota"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 10) 1)
            config {:storage storage
                    :limit limit}
            handler (wrap-stacking-rate-limit default-response-handler config)
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))
        (is (= (rate-limit rsp-b) 1))
        (is (= (remaining rsp-b) 0))
        (is (some? (retry-after rsp-b)))))

    (testing "with replenished quota"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 1) 1)
            config {:storage storage
                    :limit limit}
            handler (wrap-stacking-rate-limit default-response-handler config)
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))

        ;; Allow the counter to expire
        (Thread/sleep 1100)

        (let [rsp-c (handler (mock/request :get "/"))]
          (is (= (:status rsp-a) 200)))))

    (testing "with custom response"
      (let [storage (create-storage storage-factory-fn)
            limit (ip-rate-limit (t/seconds 10) 1)
            config {:storage storage
                    :limit limit
                    :response-builder custom-response-builder}
            handler (wrap-stacking-rate-limit default-response-handler config)
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))

        (is (= (:status rsp-b) 418))
        (is (= (rate-limit rsp-b) 1))
        (is (= (remaining rsp-b) 0))
        (is (some? (retry-after rsp-b)))))))

(deftest ^:redis test-multiple-stacked-rate-limits
  (doseq [storage-factory-fn [s/local-storage
                              #(redis/redis-storage {:spec {:host "localhost"
                                                            :port 6379}})]]
    (testing "with available quota"
      (let [storage (create-storage storage-factory-fn)
            first-limit (ip-rate-limit (t/seconds 10) 10)
            first-config {:storage storage
                          :limit first-limit}
            second-limit (->MethodRateLimit #{:get} (t/seconds 10) 100)
            second-config {:storage storage
                          :limit second-limit}
            handler (-> default-response-handler
                        (wrap-stacking-rate-limit first-config)
                        (wrap-stacking-rate-limit second-config))
            ;; allowed by first-limit and increments second-limit
            rsp (handler (mock/request :get "/"))]

        (is (= (:status rsp) 200))
        (is (= (rate-limit rsp) 10))
        (is (= (remaining rsp) 9))))

    (testing "with exhausted first quota"
      (let [storage (create-storage storage-factory-fn)
            first-limit (ip-rate-limit (t/seconds 10) 1)
            first-config {:storage storage
                          :limit first-limit}
            second-limit (->MethodRateLimit #{:get} (t/seconds 10) 100)
            second-config {:storage storage
                           :limit second-limit}
            handler (-> default-response-handler
                        (wrap-stacking-rate-limit second-config)
                        (wrap-stacking-rate-limit first-config))
            ;; :post will not match second-limit, so we end up incrementing
            ;; first-limit count
            rsp-a (handler (mock/request :post "/"))
            ;; :get would match second-limit, but first-limit (IP
            ;; limit) is exhausted, so the request is denied
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))
        (is (= (rate-limit rsp-b) 1))
        (is (= (remaining rsp-b) 0))
        (is (some? (retry-after rsp-b)))))

    (testing "with exhausted second quota"
      (let [storage (create-storage storage-factory-fn)
            first-limit (ip-rate-limit (t/seconds 10) 100)
            first-config {:storage storage
                          :limit first-limit
                          :label "first"}
            second-limit (->MethodRateLimit #{:get} (t/seconds 10) 1)
            second-config {:storage storage
                           :limit second-limit
                           :label "second"}
            handler (-> default-response-handler
                        (wrap-stacking-rate-limit second-config)
                        (wrap-stacking-rate-limit first-config))
            ;; first req exhausts second-limit and the second req is
            ;; denied
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))
        (is (= (rate-limit rsp-b) 1))
        (is (= (remaining rsp-b) 0))
        (is (some? (retry-after rsp-b)))))

    (testing "with replenished first quota"
      (let [storage (create-storage storage-factory-fn)
            first-limit (ip-rate-limit (t/seconds 10) 1)
            first-config {:storage storage
                          :limit first-limit
                          :label "first"}
            second-limit (->MethodRateLimit #{:get} (t/seconds 10) 100)
            second-config {:storage storage
                           :limit second-limit
                           :label "second"}
            handler (-> default-response-handler
                        (wrap-stacking-rate-limit second-config)
                        (wrap-stacking-rate-limit first-config))
            ;; :post will not match second-limit, so we end up incrementing
            ;; first-limit count
            rsp-a (handler (mock/request :post "/"))
            ;; :get would match second-limit, but first-limit (IP
            ;; limit) is exhausted, so the request is denied
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))

        ;; Allow the counter to expire
        (Thread/sleep 1100)

        (let [rsp-c (handler (mock/request :get "/"))]
          (is (= (:status rsp-a) 200)))))

    (testing "with replenished second quota"
      (let [storage (create-storage storage-factory-fn)
            first-limit (ip-rate-limit (t/seconds 10) 100)
            first-config {:storage storage
                          :limit first-limit
                          :label "first"}
            second-limit (->MethodRateLimit #{:get} (t/seconds 10) 1)
            second-config {:storage storage
                           :limit second-limit
                           :label "second"}
            handler (-> default-response-handler
                        (wrap-stacking-rate-limit second-config)
                        (wrap-stacking-rate-limit first-config))
            ;; first req exhausts second-limit and the second req is
            ;; denied
            rsp-a (handler (mock/request :get "/"))
            rsp-b (handler (mock/request :get "/"))]

        (is (= (:status rsp-a) 200))
        (is (= (:status rsp-b) 429))

        ;; Allow the counter to expire
        (Thread/sleep 1100)

        (let [rsp-c (handler (mock/request :get "/"))]
          (is (= (:status rsp-a) 200)))))))
