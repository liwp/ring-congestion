(ns congestion.limits)

(defprotocol RateLimit
  (get-quota [self req])
  (get-key [self req])
  (get-ttl [self req]))

(defrecord IpRateLimit [id quota ttl]
  RateLimit
  (get-quota [self req]
    quota)
  (get-key [self req]
    (str (.getName (type self)) id "-" (:remote-addr req)))
  (get-ttl [self req]
    ttl))
