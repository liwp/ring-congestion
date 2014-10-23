(ns congestion.storage)

(defprotocol Storage
  "A protocol describing the interface for storage backends.

  `get-count` is used to read the current counter value for a given key.

  `increment-count` is used to increment the counter for a given
  key. This function is responsible also for creating the counter if
  it doesn't exist already, and for scheduling the counter to expire
  after the provided timeout.

  `counter-expiry` is used to return a timestamp of when the counter
  expired, ie when the rate limit is reset again."

  (get-count [self key])
  (increment-count [self key timeout])
  (counter-expiry [self key]))

(defn- expire-key
  "Update the state map to reflect the expiry of a counter."
  [state key]
  (-> state
      (update-in [:counters] dissoc key)
      (update-in [:timeouts] dissoc key)))

(defn- schedule-expiry
  "Schedule a counter to be removed after the provided timeout."
  [state-atom key timeout-in-sec]
  (future
    (Thread/sleep (* timeout-in-sec 1000))
    (swap! state-atom expire-key key)))

(defn- increment-key
  "Increment the counter in the state map.

  If the counter didn't exist already, we also record the time when
  the counter expires."
  [state key timeout-in-sec]
  (if (get-in state [:counters key])
    (update-in state [:counters key] inc)
    (-> state
        (assoc-in [:counters key] 1)
        (assoc-in [:timeouts key] (java.util.Date. (+ (.getTime (java.util.Date.))
                                                      (* timeout-in-sec 1000)))))))

(defrecord LocalStorage [state]
  Storage
  (get-count [self key]
    (get-in @state [:counters key] 0))

  (increment-count [self key timeout]
    (let [new-state (swap! state increment-key key timeout)
          schedule-expiry? (= 1 (get-in new-state [:counters key]))]
      (when schedule-expiry?
        (schedule-expiry state key timeout)
        nil)))

  (counter-expiry [self key]
    (get-in @state [:timeouts key])))
