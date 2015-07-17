(defproject fun-params "0.1.0"
  :description ""
  :url "https://github.com/wunderlist/fun-params"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :min-lein-version "2.3.0"
  :dependencies [
      [org.clojure/clojure "1.6.0"]
      [org.clojure/data.json "0.2.6"]]
  :aliases {
    "test" ["midje"]}
  :profiles {
    :dev {
      :dependencies [[midje "1.6.3"]]
      :plugins      [[lein-midje "3.1.3"]]}})
