(ns congestion.responses
  (:require [clj-time.format :as f]))

(def default-response
  "The default 429 response."
  {:headers {"Content-Type" "application/json"}
   :body "{\"error\": \"Too Many Requests\"}"})

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
  [rsp key]
  (assoc rsp ::rate-limit-applied key))

(defn add-retry-after-header
  [rsp retry-after]
  (assoc-in rsp
            [:headers "Retry-After"]
            (time->str retry-after)))

(defn too-many-requests-response
  ([key retry-after]
     (too-many-requests-response default-response key retry-after))

  ([rsp key retry-after]
     (let [rsp (-> rsp
                   (add-retry-after-header retry-after)
                   (rate-limit-response key))]
       (merge {:status 429} rsp))))
