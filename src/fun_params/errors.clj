(ns fun-params.errors)

(defn missing
  [param]
  {:status 400,
   :errors [
    {:type "missing_parameter"
     :param param
     :title (str "Missing parameter: `" (name param) "`")}]})

(defn not-a-valid-number
  [param]
  {:status 400,
   :errors [
    {:type "invalid_type"
     :param param
     :title (str "Not a valid number: `" (name param) "`")}]})

(defn expected-string
  [param]
  {:status 400,
   :errors [
    {:type "invalid_type"
     :param param
     :title (str "Expected type `string` for param `" (name param) "`")}]})

(defn expected-array
  [param]
  {:status 400,
   :errors [
    {:type "invalid_type"
     :param param
     :title (str "Expected type `array` for param `" (name param) "`")}]})

(defn expected-object
  [param]
  {:status 400,
   :errors [
    {:type "invalid_type"
     :param param
     :title (str "Expected type `object` for param `" (name param) "`")}]})

(defn expected-non-empty
  [param]
  {:status 400,
   :errors [
    {:type "invalid_value"
     :param param
     :title (str "Expected non-empty value for param `" (name param) "`")}]})
