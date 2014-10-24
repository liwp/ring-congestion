(ns congestion.middleware
  (:require [congestion.limits :as l]
            [congestion.responses :as r]
            [congestion.storage :as s]))

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
        (r/too-many-requests-response counter-state)
        (let [rsp (handler req)]
          (if (r/rate-limit-applied? rsp)
            rsp
            (do
              (increment-counter storage counter-state)
              (r/rate-limit-response rsp counter-state))))))))
