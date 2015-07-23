(ns fun-params.middleware
  (:require [clojure.data.json :as json]))

(defn wrap-passthrough-error-as-json
  [handler]
  (fn [req]
    (let [response (handler req)]
      (if-let [errors (:errors response)]
        (assoc response :body (json/write-str {:errors errors}))
        response))))
