(ns congestion.responses-test
  (:require [clj-time.coerce :as c]
            [congestion.responses :as r]
            [clojure.test :refer :all]))

(def response
  {:status 200
   :headers {"Content-Type" "text/plain"}
   :body "Hello, world!"})

(deftest ^:unit test-rate-limit-applied?
  (testing "rate-limit-applied?"
    (doseq [[rsp res] [[{} false]
                       [{::r/rate-limit-applied "limit-key"} true]
                       [{::r/rate-limit-applied nil} false]]]
      (testing (str "with " rsp)
        (is (= (r/rate-limit-applied? rsp) res))))))

(deftest ^:unit test-rate-limit-response
  (testing "rate-limit-response"
    (let [rsp (r/rate-limit-response response "limit-key" 5 10)
          headers (:headers rsp)]
      (is (= (::r/rate-limit-applied rsp) "limit-key"))
      (is (= (:status rsp) 200))
      (is (= (:body rsp) "Hello, world!"))
      (is (= (headers "Content-Type") "text/plain"))
      (is (= (headers "X-RateLimit-Limit") "10"))
      (is (= (headers "X-RateLimit-Remaining" "5"))))))

(deftest ^:unit test-add-retry-after-header
  (testing "add-retry-after-header"
    (let [retry-after (c/from-date #inst "2014-12-31T12:34:56Z")
          rsp (r/add-retry-after-header {} retry-after)]
      (is (= (get-in rsp [:headers "Retry-After"])
             "Wed, 31 Dec 2014 12:34:56 GMT")))))

(deftest ^:unit test-too-many-requests-response
  (testing "too-many-requests-response"
    (testing "with default response"
      (let [retry-after (c/from-date #inst "2014-12-31T12:34:56Z")
            rsp (r/too-many-requests-response "limit-key" 10 retry-after)
            headers (:headers rsp)]
        (is (= (::r/rate-limit-applied rsp) "limit-key"))
        (is (= (:status rsp) 429))
        (is (= (:body rsp) "{\"error\": \"Too Many Requests\"}"))
        (is (= (headers "Content-Type") "application/json"))
        (is (= (headers "X-RateLimit-Limit") "10"))
        (is (= (headers "X-RateLimit-Remaining" "0")))
        (is (= (headers "Retry-After" "Wed, 31 Dec 2014 12:34:56 GMT")))))

    (testing "with custom response"
      (let [custom-rsp {:headers {"Content-Type" "text/plain"}
                        :body "Hello, World!"
                        :status 418}
            retry-after (c/from-date #inst "2014-12-31T12:34:56Z")
            rsp (r/too-many-requests-response custom-rsp "limit-key" 10
                                              retry-after)
            headers (:headers rsp)]
        (is (= (::r/rate-limit-applied rsp) "limit-key"))
        (is (= (:status rsp) 418))
        (is (= (:body rsp) "Hello, World!"))
        (is (= (headers "Content-Type") "text/plain"))
        (is (= (headers "X-RateLimit-Limit") "10"))
        (is (= (headers "X-RateLimit-Remaining" "0")))
        (is (= (headers "Retry-After" "Wed, 31 Dec 2014 12:34:56 GMT")))))))
