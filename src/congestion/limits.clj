(ns congestion.limits)

(defprotocol RateLimit
  (get-quota [self req])
  (get-key [self req])
  (get-period [self req]))

(defrecord IpRateLimit [period quota]
  RateLimit
  (get-quota [self req]
    quota)
  (get-key [self req]
    (str ::ip "-" (:remote-addr req)))
  (get-period [self req]
    period))
