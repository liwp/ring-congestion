(defproject listora/ring-congestion "0.1.1"
  :description "Rate limiting ring middleware"
  :url "https://github.com/listora/ring-congestion"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :scm {:name "git"
        :url "https://github.com/listora/again"}
  ;;:deploy-repositories [["releases" :clojars]]
  :repositories {"releases" {:url "s3p://eindx-maven/releases/"
                             :username [:gpg :env/aws_access_key_id]
                             :passphrase [:gpg :env/aws_secret_access_key]}
                 "snapshots" {:url "s3p://eindx-maven/snapshots/"
                              :username [:gpg :env/aws_access_key_id]
                              :passphrase [:gpg :env/aws_secret_access_key]}}
  :dependencies [[clj-time "0.8.0" :exclusions [org.clojure/clojure]]
                 [com.taoensso/carmine "2.7.1" :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.6.0"]]

  :profiles {:dev {:dependencies [[compojure "1.2.1"]
                                  [ring-mock "0.1.5"]]

                   :plugins [[jonase/eastwood "0.1.5"]
                             [listora/whitespace-linter "0.1.0"]
                             [s3-wagon-private "1.1.2"]]

                   :eastwood {:exclude-linters [:deprecations :unused-ret-vals]}

                   :aliases {"ci" ["do" ["test"] ["lint"]]
                             "lint" ["do" ["whitespace-linter"] ["eastwood"]]}}}

  :test-selectors {:default (complement :redis)
                   :redis :redis
                   :unit :unit
                   :all (fn [_] true)})
