(ns fun-params.core
  "Functional parameter validation composition.
   An alternative DSL to api-controller.params

   The main ideas in fn-params are:

   1. Declarative:
      (require ['fun-params.core :as p])
      (def valid-name (p/string :name))

   2. Composable:
      (def valid-name (p/optional (p/non-empty (p/string :name))))

   3. Callback-based:
      (defn show
        [req]
        (valid-name req
          (fn [name]
            ; Handler code goes here,
            ; will only be called if name is valid

   4. Overloaded:
      (def valid-id (p/integer :id))
      (defn show
        [req]
        (valid-id req
          (fn [id]
            ; This is the normal, explicit way to use p/integer
            ; however, this implicit way is also supported:
      (defn show
        [req]
        (p/integer req :id
          (fn [id]

   5. Validators can transform data (e.g. string -> int but also more complex when needed)
   6. Support structural validation of JSON, see array and dictionary functions
   7. Easily extendable by application-specific validators

   Validators functions have arguments [req ... success-fn]

   1. In case of failure: return a response with status 400 themselves
   2. In case or success: call success-fn and return the returned value
   3. `...` are specific arguments for the validator, such as parameter names

   Additionally, validator functions provide higher-order arities
   so that they return functions which behave as described above.
   That is useful for composing validators.

   Important gotcha: `require-all` and `either` will return the first
   validation error but they might still evaluate more than one validator
   because Clojure is not strictly lazy.

   Arity/argument overview for functions in this namespace:

   Base-validators: string, integer
     [] => so it can be used as per-element-valid-fn in collections, (array :foo (string))
     [param]
     [req param success-fn]

   Collection-validators: array, dictionary
     [param per-element-valid-fn]
     [req param per-element-valid-fn success-fn]

   Compose-validators: optional, non-empty (no extras), replace-nil and wrap (have extras)
     [&extras valid-fn]
     [param &extras valid-fn]
     [req param &extras valid-fn success-fn]

   require-all, require-any:
     [validator-fn-seq]
     [req validator-fn-seq success-fn]

   either:
     [valid-1-fn valid-2-fn]
     [req valid-1-fn valid-2-fn success-fn]

   Implementation Details:

   fn-params uses some Clojure magic by attaching metadata to the validator functions.
   When composing functions, this metadata is used to determine which parameter
   should be checked for optional, empty-ness, etc.

   This metadata is also used in either and require-all to build a map of validated
   parameters which is passed on to the success-fn callback.

   Related Work (other Clojure validation libraries for reference):

    - https://github.com/leonardoborges/bouncer
    - https://github.com/JulianBirch/arianna
    - https://github.com/thi-ng/validate
    - https://github.com/jkk/formative
   "
   (:require [fun-params.errors :as errors]
             [clojure.set :refer [union]]))

(defn get-param
  "Either returns a keyword-key's value or a string-key's value.
   Ring's JSON middleware sets params from JSON via string keys but route- and GET-
   params are set as keywords. This function hides that difference."
  [req param & [na]]
  (let [kw-val     (get-in req [:params param] ::NA)
        string-val (get-in req [:params (name param)] ::NA)]
    (cond
      (not= kw-val ::NA)     kw-val
      (not= string-val ::NA) string-val
      :else                  na)))

(defn- to-int
  "Like parseInt but throws no exceptions. I wish this was the default."
  [thing]
  (if-not thing
    nil
    (if (integer? thing)
      thing
      (try (Integer/parseInt thing)
        (catch java.lang.NumberFormatException e
          nil)))))

(defn- throw-missing-metadata
  [valid-fn composer]
  (throw
    (ex-info "validator function does not have parameter metadata"
             {:fn valid-fn, :composer composer})))

(defn- param-for
  "Throws an excpetion if valid-fn has no associated param metadata."
  [valid-fn composer-name]
  (if-let [param (-> valid-fn meta ::param)]
    param
    (throw-missing-metadata valid-fn composer-name)))

(defn- params-for
  "Returns a set of keywords that contains all parameters that valid-fn might check."
  [valid-fn]
  (if-let [param (-> valid-fn meta ::param)]
    #{param}
    (-> valid-fn meta ::param-set)))

(defn- succeeds-with-multiple-params?
  ([valid-fn]
    (-> valid-fn meta ::multiple-params))
  ([valid-1-fn valid-2-fn]
    (or (succeeds-with-multiple-params? valid-1-fn)
        (succeeds-with-multiple-params? valid-2-fn))))

(defn- with-meta-lambda-recur
  "Wraps a new lambda with parameter metadata.
   The new lambda function will call `recur-fn` but will prepend
   a `req` and append a `success-fn` argument to the recursive call."
  [recur-fn param & extra-args]
  (with-meta
    (fn [req success-fn]
      (apply recur-fn (concat [req param] extra-args [success-fn])))
    {::param param}))

(defn integer
  "Coerces from string or keeps ints.
   Supported success-fn:
    [param]

   Allows for two common patterns:

    (def valid-id (fn-params/integer :id))
    (defn show
      [req]
      (valid-id req
        (fn [id]
          ...
   or:

    (defn show
      [req]
      (fn-params/integer req :id
        (fn [id]
          ..."
  ([param]
    (with-meta-lambda-recur integer param))
  ([req param success-fn]
    (let [value (get-param req param)
          num  (to-int value)]
      (if num
        (success-fn num)
        (errors/not-a-valid-number param)))))

(defn string
  "Ensures `param` is a string.
   If you need to ensure a length, combine this with `non-empty`.
   Supported success-fn:
    [param]"
  ([param]
    (with-meta-lambda-recur string param))
  ([req param success-fn]
    (let [value (get-param req param)]
      (if (string? value)
        (success-fn value)
        (errors/expected-string param)))))

(defn non-empty
  "Works for strings, maps/dictionaries and arrays/vectors.
   Ensures that the collection is not empty.
   Supported success-fn:
    [param]"
  ([valid-fn]
    (non-empty (param-for valid-fn 'non-empty) valid-fn))
  ([param valid-fn]
    (with-meta-lambda-recur non-empty param valid-fn))
  ([req param valid-fn success-fn]
    (let [value (get-param req param ::NA)]
      (if (and (or (string? value) (coll? value)) (not (empty? value)))
        (valid-fn req success-fn)
        (errors/expected-non-empty param)))))

(defn- validate-each-element
  "Helper for array and dictionary, ensures that all
   elements/entries in a sequence or map are valid."
  [array per-element-valid-fn transform-seq-fn success-fn]
  (let [success-vals           (atom [])
        success-indices        (atom #{})
        per-element-success-fn (fn [index element]
                                  (swap! success-vals conj element)
                                  (swap! success-indices conj index)
                                  element)
        mapper                 #(per-element-valid-fn %2 (partial per-element-success-fn %1))
        results                (map-indexed mapper array)
        first-error-index      (delay (apply min (clojure.set/difference
                                                   (set (range (count array)))
                                                   @success-indices)))]
    (if (not= (count results) (count @success-vals))
      (nth results @first-error-index)
      (success-fn (transform-seq-fn @success-vals)))))

(defn array
  "Check if param is `sequential?` or a `array` in JSON lingo.
   per-element-valid-fn: (fn [val success-fn]) => {:status 400} | (success-fn val')
   val' is a possible transform of val.
   Supported success-fn:
    [param]"
  ([param per-element-valid-fn]
    (with-meta-lambda-recur array param per-element-valid-fn))
  ([req param per-element-valid-fn success-fn]
    (let [value (get-param req param)]
      (if (sequential? value)
        (validate-each-element value per-element-valid-fn identity success-fn)
        (errors/expected-array param)))))

(defn dictionary
  "Check if param is a map/dictionary or `object` in JSON lingo.
   per-entry-valid-fn: (fn [[key val] success-fn]) => {:status 400} | (success-fn [key' val'])
   key' and val' are possible transformations of val'.
   Supported success-fn:
    [param]"
  ([param per-entry-valid-fn]
    (with-meta-lambda-recur dictionary param per-entry-valid-fn))
  ([req param per-entry-valid-fn success-fn]
    (let [value (get-param req param)]
      (if (map? value)
        (validate-each-element value per-entry-valid-fn (partial into {}) success-fn)
        (errors/expected-object param)))))

(defn optional
  "Calls `success-fn` iff when the requested parameter is missing
   or when `valid-fn` validates that parameter.
   Supported success-fn:
    [param]"
  ([valid-fn]
    (optional (param-for valid-fn 'optional) valid-fn))
  ([param valid-fn]
    (with-meta-lambda-recur optional param valid-fn))
  ([req param valid-fn success-fn]
    (let [value (get-param req param ::NA)]
      (if (= ::NA value)
        (success-fn nil)
        (valid-fn req success-fn)))))

(defn replace-nil
  "Replaces nil values for `param` with a `fallback-value`.
   Note: A not-existing parameter value is not replaced, only an explicit `nil`.
   Supported success-fn:
    [param]"
  ([fallback-value valid-fn]
    (replace-nil (param-for valid-fn 'replace-nil) fallback-value valid-fn))
  ([param fallback-value valid-fn]
    (with-meta-lambda-recur replace-nil param fallback-value valid-fn))
  ([req param fallback-value valid-fn success-fn]
    (let [value   (get-param req param ::NA)
          new-req (if (nil? value)
                    (assoc-in req [:params param] fallback-value)
                    req)]
      (valid-fn new-req success-fn))))

(defn wrap
  "Wraps another `wrapping-fn` around `valid-fn` so that the output
   of `valid-fn` can be processed by `wrapping-fn` before the final `success-fn`
   is called."
  ([wrapping-fn valid-fn]
    (wrap (param-for valid-fn 'wrap) wrapping-fn valid-fn))
  ([param wrapping-fn valid-fn]
    (with-meta
      (fn [req success-fn]
        (valid-fn req #(wrapping-fn % success-fn)))
      {::param param})))

(defn mark-param
  "Attaches ::param metadata to `valid-fn`.
   Usefull to annotate custom validators so that they can be used
   together with require-all and require-any."
  [param valid-fn]
  (with-meta
    valid-fn
    {::param param}))

(defn- arg-count
  [func]
  (let [methods (.getDeclaredMethods (class func))
        method  (first (filter #(#{"invoke" "doInvoke"} (.getName %)) methods))
        params  (.getParameterTypes method)]
    (alength params)))

(defn- validated-param-map
  "Returns a map {:param result, ...}
   by scanning through the metadata of all valid-fns in `validator-fn-seq`
   and matching it with the `results`.
   The exceptions in this function are asserts to catch inconsitencies when
   combining multiple validators. If they throw during usage, then there
   is a bug in fn-params."
  [validator-fn-seq results]
  (let [result-per-fn (zipmap validator-fn-seq results)
        all-params    (apply concat (map params-for validator-fn-seq))]
    (when (not= (count all-params) (count (set all-params)))
      (throw (ex-info "Parameters are validated more than once" {:params all-params})))
    (into
      {}
      (for [[valid-fn result-obj] result-per-fn
            :let [params     (params-for valid-fn)
                  _          (when (empty? params)
                               (throw-missing-metadata valid-fn 'require-all))
                  _          (when (and (< 1 (count params)) (not (map? result-obj)))
                               (throw (ex-info "Multiple params but result not a map." {})))
                  result-map (if (= 1 (count params))
                               {(first params) result-obj}
                               result-obj)]
            param params
            :let [result (get result-map param ::NA)]
            :when (not= result ::NA)]
        [param result]))))

(defn- validation-results
  [req validator-fn-seq]
  (map
    (fn [valid-fn]
      (let [result (atom ::NA)]
        {::response (valid-fn req #(reset! result %))
         ::result   @result
         ::error?   (= ::NA @result)
         ::fn       valid-fn} ))
    validator-fn-seq))

(defn require-all
  "If all validators pass (execute the func and don't return a body)
   then this will call func with the parameters.
   Supported success-fn:
    []
    [{:keys [param1 param2 ...]}]
    [param1 param2 ...] ;; EXCEPT when require-{all,any} are nested in `validator-fn-seq`"
  ([validator-fn-seq]
    (with-meta
      (fn [req success-fn]
        (require-all req validator-fn-seq success-fn))
      {::multiple-params true
       ::param-set       (apply union (map params-for validator-fn-seq))}))
  ([req validator-fn-seq success-fn]
    (let [results      (validation-results req validator-fn-seq)
          success-args (arg-count success-fn)]
      (if-let [err (first (filter ::error? results))]
        (::response err)
        (cond
          (= 0 success-args)
            (success-fn)
          (= 1 success-args)
            (success-fn (validated-param-map validator-fn-seq (map ::result results)))
          (and (not= 1 success-args)
               (some true? (map succeeds-with-multiple-params? validator-fn-seq)))
            (throw (ex-info "success-fn must take exactly 1 map-argument when multiple params are used in require-all"
                            {:fn success-fn :args success-args}))
          :else
            (apply success-fn (map ::result results)))))))

(defn require-any
  "Ensures that at least one validator passes,
   will pass on all successful params to success-fn.
   Supported success-fn:
    []
    [{:keys [param1 param2 ...]}]"
  ([validator-fn-seq]
    (with-meta
      (fn [req success-fn]
        (require-any req validator-fn-seq success-fn))
      {::multiple-params true
       ::param-set       (apply union (map params-for validator-fn-seq))}))
  ([req validator-fn-seq success-fn]
    (let [results                (validation-results req validator-fn-seq)
          successes              (filter (complement ::error?) results)
          prefer-existing-params #(some->> % ::fn meta ::param (get-param req) some?)
          errors                 (sort-by prefer-existing-params (filter ::error? results))
          success-args           (arg-count success-fn)]
      (if (empty? successes)
        (::response (last errors))
        (case success-args
          0 (success-fn)
          1 (success-fn (validated-param-map (map ::fn successes) (map ::result successes)))
            (throw (ex-info "require-any expects a success-fn with 0 or 1 argument" {:success-fn success-fn})))))))

(defn- wrap-params-with-map
  [multiple-params? valid-fn success-fn]
  (if (zero? (arg-count success-fn))
    (fn [_] (success-fn))
    (if (and multiple-params? (not (succeeds-with-multiple-params? valid-fn)))
      #(success-fn {(param-for valid-fn 'either) %})
      success-fn)))

(defn either
  "Ensures that either valid-1-fn or valid-2-fn succeed
   in validating their parameters.
   Supported success-fn:
    []
    [valid-param]
    [{:keys [param1 param2 ...]}]"
  ([valid-1-fn valid-2-fn]
    (with-meta
      (fn [req success-fn]
        (either req valid-1-fn valid-2-fn success-fn))
      {::multiple-params (succeeds-with-multiple-params? valid-1-fn valid-2-fn)
       ::param-set       (union (params-for valid-1-fn) (params-for valid-2-fn))}))
  ([req valid-1-fn valid-2-fn success-fn]
    (let [success-val      (atom ::NA)
          multiple-params? (succeeds-with-multiple-params? valid-1-fn valid-2-fn)
          success-1-fn     (wrap-params-with-map multiple-params? valid-1-fn success-fn)
          success-2-fn     (wrap-params-with-map multiple-params? valid-2-fn success-fn)]
      (valid-1-fn req #(reset! success-val %))
      (if (not= ::NA @success-val)
        (success-1-fn @success-val)
        (valid-2-fn req success-2-fn)))))
