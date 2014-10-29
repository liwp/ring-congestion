(ns congestion.middleware
  (:require [congestion.limits :as l]
            [congestion.responses :as r]
            [congestion.storage :as s]))

(defn- read-counter
  [storage limit req]
  (if-let [key (l/get-key limit req)]
    (let [period (l/get-period limit req)
          quota (l/get-quota limit req)
          current-count (s/get-count storage key)
          remaining-requests (- quota current-count 1)]
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
         :quota quota}))))

(defn- increment-counter
  [storage counter-state]
  (let [key (:key counter-state)
        period (:period counter-state)]
    (s/increment-count storage key period)))

(defn- build-error-response
  [response-builder counter-state]
  (if response-builder
    (response-builder counter-state)
    (r/too-many-requests-response counter-state)))

(defn wrap-stacking-rate-limit
  [handler {:keys [storage limit response-builder]}]
  (fn [req]
    (let [counter-state (read-counter storage limit req)]
      (if (:rate-limit-exhausted? counter-state)
        (build-error-response response-builder counter-state)
        (let [rsp (handler req)]
          (if (or (r/rate-limit-applied? rsp)
                  (nil? counter-state))
            rsp
            (do
              (increment-counter storage counter-state)
              (r/rate-limit-response rsp counter-state))))))))

(defn wrap-rate-limit
  [handler {:keys [storage limit response-builder]}]
  (fn [req]
    (let [counter-state (read-counter storage limit req)]
      (if (:rate-limit-exhausted? counter-state)
        (build-error-response response-builder counter-state)
        (do
          (increment-counter storage counter-state)
          (-> req
              handler
              (r/rate-limit-response counter-state)))))))

;; Expose lib internals as delegates so that the user needs to import
;; only one namespace.

(defn ip-rate-limit
  [period quota]
  (l/->IpRateLimit period quota))

(defn too-many-requests-response
  [& args]
  (apply r/too-many-requests-response args))
