

# fun-params

HTTP parameter parsing library for Clojure/Ring based on function composition.


## Releases and Dependency Information

fun-params is released via [Clojars](https://clojars.org/fun-params). The Latest stable release is 0.1.0

[Leiningen](https://github.com/technomancy/leiningen) dependency information:

```clojure
[fun-params "0.1.0"]
```

Maven dependency information:

```xml
<dependency>
  <groupId>fun-params</groupId>
  <artifactId>fun-params</artifactId>
  <version>0.1.0</version>
</dependency>
```


## Concepts

The main ideas in fun-params are:

### 1. Declarative:

```clojure
(require ['fun-params.core :as p])

(def valid-name (p/string :name))
```

### 2. Composable:

```clojure
(def valid-name (p/optional (p/non-empty (p/string :name))))
```

### 3. Callback-based:

```clojure
(defn show
  [req]
  (valid-name req
    (fn [name]
      ; Handler code goes here,
      ; will only be called if name is valid
```

### 4. Overloaded:

```clojure
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
```

### 5. Validators can transform data (e.g. string -> int but also more complex when needed)
### 6. Support structural validation of JSON, see array and dictionary functions
### 7. Easily extendable by application-specific validators


## Usage

Validators functions have arguments `[req ... success-fn]`

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

Base-validators: `string`, `integer`

  - `[]` => so it can be used as per-element-valid-fn in collections, (array :foo (string))
  - `[param]`
  - `[req param success-fn]`

Collection-validators: `array`, `dictionary`

  - `[param per-element-valid-fn]`
  - `[req param per-element-valid-fn success-fn]`

Compose-validators: `optional`, `non-empty` (no extras), `replace-nil` and `wrap` (have extras)

  - `[&extras valid-fn]`
  - `[param &extras valid-fn]`
  - `[req param &extras valid-fn success-fn]`

`require-all`, `require-any`:

  - `[validator-fn-seq]`
  - `[req validator-fn-seq success-fn]`

`either`:

  - `[valid-1-fn valid-2-fn]`
  - `[req valid-1-fn valid-2-fn success-fn]`


### Implementation Details:

fun-params uses some Clojure magic by attaching metadata to the validator functions.
When composing functions, this metadata is used to determine which parameter
should be checked for optional, empty-ness, etc.

This metadata is also used in `either` and `require-all` to build a map of validated
parameters which is passed on to the `success-fn` callback.


## Related Work

Other Clojure validation libraries for reference:

  - https://github.com/leonardoborges/bouncer
  - https://github.com/JulianBirch/arianna
  - https://github.com/thi-ng/validate
  - https://github.com/jkk/formative


## License

Copyright © 2014-2015 6 Wunderkinder GmbH.

Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
