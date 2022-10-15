(defproject listora/ring-congestion "0.1.3-SNAPSHOT"
  :description "Rate limiting ring middleware"
  :url "https://github.com/listora/ring-congestion"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/liwp/ring-congestion"}
  :deploy-repositories [["releases" :clojars]]

  :dependencies [[clj-time "0.15.2" :exclusions [org.clojure/clojure]]
                 [com.taoensso/carmine "3.1.0" :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.10.0"]]

  :profiles {:dev {:dependencies [[compojure "1.7.0"]
                                  [ring/ring-mock "0.4.0"]]

                   :plugins [[jonase/eastwood "1.3.0"]]

                   :aliases {"ci" ["do" ["test"] ["lint"]]
                             "lint" ["eastwood"]}}}

  :test-selectors {:default (complement :redis)
                   :redis :redis
                   :unit :unit
                   :all (fn [_] true)})
