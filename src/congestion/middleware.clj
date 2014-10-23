(ns congestion.middleware
  (:require [congestion.limits :as l]
            [congestion.storage :as s]))

(defn- rate-limit-applied?
  [rsp]
  (-> rsp
      ::rate-limit-applied
      true?))

(defn- rate-limit-response
  [rsp state]
  (let [headers {"X-RateLimit-Limit" (str (:quota state))
                 "X-RateLimit-Remaining" (str (:remaining-requests state))}]
    (-> rsp
        (update-in [:headers] merge headers)
        (assoc ::rate-limit-applied true))))

(defn- too-many-requests-response
  [state]
  (-> {:status 429
       :headers
       {"Content-Type" "application/json"
        "Retry-After" (str (:retry-after state))} ;; TODO: render to string
       :body "{\"error\": \"Too Many Requests\"}"}
      (rate-limit-response state)))

(defn read-counter
  [storage limit req]
  (let [key (l/get-key limit req)
        period (l/get-period limit)
        quota (l/get-quota limit)
        current-count (s/get-count storage key)
        remaining-requests (- quota current-count)]
    (if (neg? remaining-requests)
      {:key key
       :period period
       :rate-limit-exhausted? true
       :remaining-requests 0
       :retry-after (s/counter-expiry storage key)
       :quota quota}
      {:key key
       :period period
       :rate-limit-exhausted? false
       :remaining-requests remaining-requests
       :quota quota})))

(defn increment-counter
  [storage counter-state]
  (let [key (:key counter-state)
        period (:period counter-state)]
    (s/increment-count storage key period)))

(defn wrap-rate-limit
  [handler storage limit]
  (fn [req]
    (let [counter-state (read-counter storage limit req)]
      (if (:rate-limit-exhausted? counter-state)
        (too-many-requests-response counter-state)
        (let [rsp (handler req)]
          (if (rate-limit-applied? rsp)
            rsp
            (do
              (increment-counter storage limit req)
              (rate-limit-response rsp counter-state))))))))
