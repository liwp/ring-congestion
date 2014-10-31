(ns congestion.middleware
  (:require [congestion.limits :as limits]
            [congestion.quota-state :as quota-state]
            [congestion.responses :as responses]))

(defn wrap-stacking-rate-limit
  [handler {:keys [storage limit response-builder]}]
  (fn [req]
    (let [quota-state (quota-state/read-quota-state storage limit req)]
      (if (quota-state/quota-exhausted? quota-state)
        (quota-state/build-error-response quota-state response-builder)
        (let [rsp (handler req)]
          (if (responses/rate-limit-applied? rsp)
            rsp
            (do
              (quota-state/increment-counter quota-state storage)
              (quota-state/rate-limit-response quota-state rsp))))))))

(defn wrap-rate-limit
  [handler {:keys [storage limit response-builder]}]
  (fn [req]
    (let [quota-state (quota-state/read-quota-state storage limit req)]
      (if (quota-state/quota-exhausted? quota-state)
        (quota-state/build-error-response quota-state response-builder)
        (do
          (quota-state/increment-counter quota-state storage)
          (->> req
              handler
              (quota-state/rate-limit-response quota-state)))))))

;; Expose lib internals as delegates so that the user needs to import
;; only one namespace.

(defn ip-rate-limit
  [period quota]
  (limits/->IpRateLimit period quota))

(defn too-many-requests-response
  ([key quota retry-after]
     (responses/too-many-requests-response key quota retry-after))
  ([rsp key quota retry-after]
     (responses/too-many-requests-response rsp key quota retry-after)))
