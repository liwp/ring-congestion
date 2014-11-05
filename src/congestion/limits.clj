(ns congestion.limits)

(defprotocol RateLimit
  (get-quota [self req])
  (get-key [self req])
  (get-period [self req]))

(defrecord IpRateLimit [id quota period]
  RateLimit
  (get-quota [self req]
    quota)
  (get-key [self req]
    (str (.getName (type self)) id "-" (:remote-addr req)))
  (get-period [self req]
    period))
