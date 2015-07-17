(ns fun-params.middleware-test
  (:require [midje.sweet :refer :all]
            [fun-params.middleware :as middleware]))

(facts "about passthrough-error-as-json"
  (fact "passes through original response if no error"
    (let [response {:body :ok}
          request {}
          handler-fn (fn [req] response)]
      ((middleware/passthrough-error-as-json handler-fn) request)
      => response))

  (fact "merges error into body"
    (let [response {:errors ["error"]}
          request {}
          handler-fn (fn [req] response)]
      ((middleware/passthrough-error-as-json handler-fn) request)
      => (contains {:body "{\"errors\":[\"error\"]}"}))))
