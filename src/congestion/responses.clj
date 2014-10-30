(ns congestion.responses
  (:require [clj-time.format :as f]))

(def ^:private time-format (f/formatter "EEE, dd MMM yyyy HH:mm:ss"))

(defn- time->str
  [time]
  ;; All HTTP timestamps MUST be in GMT and UTC == GMT in this case.
  (str (f/unparse time-format time) " GMT"))

(defn rate-limit-applied?
  [rsp]
  (-> rsp
      ::rate-limit-applied
      some?))

(defn rate-limit-response
  [rsp counter-state]
  (let [headers {"X-RateLimit-Limit"
                 (str (:quota counter-state))
                 "X-RateLimit-Remaining"
                 (str (:remaining-requests counter-state))}]
    (-> rsp
        (update-in [:headers] merge headers)
        (assoc ::rate-limit-applied (:key counter-state)))))

(defn add-retry-after-header
  [rsp counter-state]
  (assoc-in rsp
            [:headers "Retry-After"]
            (time->str (:retry-after counter-state))))

(defn too-many-requests-response
  ([counter-state]
     (too-many-requests-response
      counter-state
      "application/json"
      "{\"error\": \"Too Many Requests\"}"))

  ([counter-state content-type body]
     (too-many-requests-response
      counter-state
      {:headers {"Content-Type" content-type}
       :body body}))

  ([counter-state rsp]
     (let [rsp (-> rsp
                   (add-retry-after-header counter-state)
                   (rate-limit-response counter-state))]
       (merge {:status 429} rsp))))
