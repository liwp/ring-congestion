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
  [rsp key remaining-requests quota]
  (let [headers {"X-RateLimit-Limit" (str quota)
                 "X-RateLimit-Remaining" (str remaining-requests)}]
    (-> rsp
        (update-in [:headers] merge headers)
        (assoc ::rate-limit-applied key))))

(defn add-retry-after-header
  [rsp retry-after]
  (assoc-in rsp
            [:headers "Retry-After"]
            (time->str retry-after)))

(defn too-many-requests-response
  ([key quota retry-after]
     (let [rsp {:headers {"Content-Type" "application/json"}
                :body "{\"error\": \"Too Many Requests\"}"}]
       (too-many-requests-response rsp key quota retry-after)))

  ([rsp key quota retry-after]
     (let [rsp (-> rsp
                   (add-retry-after-header retry-after)
                   (rate-limit-response key 0 quota))]
       (merge {:status 429} rsp))))
