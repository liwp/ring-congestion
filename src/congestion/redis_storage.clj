(ns congestion.redis-storage
  (:require [clj-time.core :as t]
            [congestion.storage :as s]
            [taoensso.carmine :as car]))

(def ttl-incr-script
  (str
   "local current = redis.call(\"incr\", KEYS[1])"
   "if tonumber(current) == 1 then"
   "  redis.call(\"expire\", KEYS[1], ARGV[1])"
   "end"))

(def prefix "congestion-")

(defn- generate-redis-key
  [key]
  (str prefix key))

(defrecord RedisStorage [conn-opts]
  s/Storage
  (get-count [self key]
    (let [redis-key (generate-redis-key key)]
      (if-let [counter (car/wcar
                        conn-opts
                        (car/get redis-key))]
        (Integer. counter)
        0)))

  (increment-count [self key ttl]
    (let [redis-key (generate-redis-key key)
          ttl-in-secs (.getStandardSeconds (.toStandardDuration ttl))]
      (car/wcar
       conn-opts
       (car/eval* ttl-incr-script 1 redis-key ttl-in-secs))))

  (counter-expiry [self key]
    (let [redis-key (generate-redis-key key)
          ttl (car/wcar conn-opts (car/ttl redis-key))]
      (if (neg? ttl)
        :expired
        (t/plus (t/now) (t/seconds ttl)))))

  (clear-counters [self]
    (loop [curr-pos "0"]
      (let [[next-pos keys] (car/wcar
                             conn-opts
                             (car/scan curr-pos "match" (str prefix "*")))]
        (when (seq keys)
          (car/wcar
           conn-opts
           (apply car/del keys)))

        (when (not= next-pos "0")
          (recur next-pos))))))

(defn redis-storage
  [conn-opts]
  (->RedisStorage conn-opts))
