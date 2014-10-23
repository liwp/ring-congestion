(ns congestion.limits)

(defprotocol RateLimit
  (get-quota [self])
  (get-key [self req])
  (get-period [self]))

(defrecord IpRateLimit [period quota]
  RateLimit
  (get-quota [self]
    quota)
  (get-key [self req]
    (str ::ip "-" (:remote-addr req)))
  (get-period [self]
    period))
