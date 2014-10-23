(defproject listora/ring-congestion "0.1.0-SNAPSHOT"
  :description "Rate limiting ring middleware"
  :url "https://github.com/listora/ring-congestion"
  :license {:distribution :manual}
  :dependencies [[com.taoensso/carmine "2.7.1" :exclusions [org.clojure/clojure]]
                 [org.clojure/clojure "1.6.0"]]
  :profiles {:dev {:dependencies [[ring/ring-core "1.3.1"]
                                  [ring-mock "0.1.5"]
                                  [com.cemerick/friend "0.2.1"]]

                   :plugins [[jonase/eastwood "0.1.4"]
                             [listora/whitespace-linter "0.1.0"]
                             [s3-wagon-private "1.1.2"]]

                   :eastwood {:exclude-linters [:deprecations :unused-ret-vals]}

                   :aliases {"ci" ["do" ["test"] ["lint"]]
                             "lint" ["do" ["whitespace-linter"] ["eastwood"]]}}}

  :repositories {"releases" {:url "s3p://eindx-maven/releases/"
                             :username [:gpg :env/aws_access_key_id]
                             :passphrase [:gpg :env/aws_secret_access_key]}
                 "snapshots" {:url "s3p://eindx-maven/snapshots/"
                              :username [:gpg :env/aws_access_key_id]
                              :passphrase [:gpg :env/aws_secret_access_key]}})
