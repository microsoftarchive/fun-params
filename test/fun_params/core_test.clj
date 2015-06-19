(ns fun-params.core-test
  (:require [midje.sweet :refer :all]
            [fun-params.core :as p]))

(defn- no-param-meta-data-fn
  [req success-fn]
  (success-fn "yay"))

(facts "about require-all"
  (fact "fails when not all valid-fns have param metadata"
    (p/require-all {} [no-param-meta-data-fn] identity)
    => (throws #"^validator function does not have parameter metadata"))

  (fact "builds params map when param metadata exists"
    (p/require-all {:params {:wat "123"}} [(p/integer :wat)] identity)
    => {:wat 123})

  (fact "supports success-fn with zero arguments"
    (p/require-all {:params {:foo "bar"}} [(p/string :foo)] (fn [] "WIN!"))
    => "WIN!")

  (fact "supports positional arguments for basic validators"
    (p/require-all {:params {:a 1, :b ""}} [(p/integer :a), (p/string :b)] (fn [a b] [b a]))
    => ["" 1])

  (fact "fails when same parameter is validated twice to avoid conflicts"
    (p/require-all {:params {:a 1}} [(p/integer :a), (p/integer :a)] identity)
    => (throws #"^Parameters are validated more than once"))

  (facts "can be nested with require-all"
    (def valid-fn (p/require-all [(p/require-all [(p/string :a) (p/string :b)])
                                  (p/require-all [(p/integer :c) (p/integer :d)])]))
    (fact "fails with no params"
      (valid-fn {} identity) => (contains {:status 400}))
    (fact "passes with all params given"
      (def params {:a "A", :b "B", :c 1, :d 2})
      (valid-fn {:params params} identity) => params)
    (fact "throws exception when unpacking into positional arguments"
      (def params {:a "A", :b "B", :c 1, :d 2})
      (valid-fn {:params params} (fn [a b & args] [a b args]))
      => (throws #"^success-fn must take exactly 1 map-argument")))

  (fact "basic nesting allows for positional arguments if possible"
    (def valid-fn (p/require-all [(p/either (p/integer :list_id) (p/string :list_name))
                                  (p/integer :user_id)]))
    (valid-fn {:params {:list_id 1, :user_id 2}} (fn [list-thing user-id] [list-thing user-id]))
    => [1 2])

  (fact "can be nested with either"
    (def valid-fn (p/require-all [(p/either (p/require-all [(p/integer :i)
                                                            (p/integer :j)])
                                            (p/require-all [(p/integer :x)
                                                            (p/integer :y)]))
                                  (p/require-all [(p/string :a)
                                                  (p/string :b)])]))
    (valid-fn {:params {:i 1, :j 2, :a "A", :b "B"}} identity)
    => {:i 1, :j 2, :a "A", :b "B"}
    (valid-fn {:params {:x 11, :y 22, :a "A", :b "B"}} identity)
    => {:x 11, :y 22, :a "A", :b "B"}
    (valid-fn {} identity)
    => (contains {:status 400})))

(facts "about string"
  (def valid-fn (p/string :doc))
  (fact "accepts a string param"
    (valid-fn {:params {:doc "abc"}} identity) => "abc")
  (fact "rejects nil"
    (valid-fn {:params {:doc nil}} identity) => (contains {:status 400}))
  (fact "rejects missing"
    (valid-fn {:params {}} identity) => (contains {:status 400}))
  (fact "rejects integers"
    (valid-fn {:params {:doc 123}} identity) => (contains {:status 400})))

(facts "about `(optional (replace-nil (string)))` composition"
  (def valid-fn (p/optional (p/replace-nil "" (p/string :doc))))
  (fact "accepts a string param"
    (valid-fn {:params {:doc "abc"}} identity) => "abc")
  (fact "replaces nil with empty string"
    (valid-fn {:params {:doc nil}} identity) => "")
  (fact "accepts when missing"
    (valid-fn {:params {}} identity) => nil)
  (fact "rejects integers"
    (valid-fn {:params {:doc 123}} identity) => (contains {:status 400})))

(facts "about array"
  (def element-valid-fn (fn [val success-fn] (if (string? val)
                                                (success-fn (Integer. val))
                                                "FAIL!")))
  (def valid-fn (p/array :assign element-valid-fn))
  (fact "accepts a empty array"
    (valid-fn {:params {:assign []}} identity) => [])
  (fact "transforms values"
    (valid-fn {:params {:assign ["123", "54"]}} identity) => [123, 54])
  (fact "rejects bad type"
    (valid-fn {:params {:assign [0]}} identity) => "FAIL!")
  (fact "returns error for first failure"
    (valid-fn {:params {:assign ["1" 666]}} identity) => "FAIL!"))

(facts "about dictionary"
  (def entry-valid-fn (fn [[key val] success-fn] (if (string? key)
                                                    (success-fn [key (Integer. val)])
                                                    "FAIL!")))
  (def valid-fn (p/dictionary :cascade entry-valid-fn))
  (fact "accepts a empty dictionary"
    (valid-fn {:params {:cascade {}}} identity) => {})
  (fact "transforms values"
    (valid-fn {:params {:cascade {"foo" "123", "bar" "456"}}} identity) => {"foo" 123, "bar" 456})
  (fact "rejects bad type"
    (valid-fn {:params {:cascade {123 "foo"}}} identity) => "FAIL!")
  (fact "returns error for first failure"
    (valid-fn {:params {:cascade {"foo" "123", 456 "x"}}} identity) => "FAIL!"))

(facts "about either"
  (fact "in-place arity works"
    (p/either {:params {:b "WIN"}} (p/integer :a) (p/string :b) identity) => "WIN")

  (facts "composing 2 single validators"
    (def valid-fn (p/either (p/string :foo) (p/integer :bar)))
    (fact "accepts :foo"
      (valid-fn {:params {:foo "FOO"}} identity) => "FOO")
    (fact "accepts :bar"
      (valid-fn {:params {:bar 123}} identity) => 123)
    (fact "supports 0 arity success-fn"
      (valid-fn {:params {:foo "FOO"}} (fn [] :bing!)) => :bing!)
    (fact "fails empty params"
      (valid-fn {:params {}} identity) => (contains {:status 400}))
    (fact "fails with :foo integer"
      (valid-fn {:params {:foo 123}} identity) => (contains {:status 400}))
    (fact "fails with :bar string"
      (valid-fn {:params {:bar "xx"}} identity) => (contains {:status 400})))

  (facts "composing single and multiple"
    (def valid-fn (p/either (p/string :foo) (p/require-all [(p/string :bar) (p/string :baz)])))
    (fact "fails empty params"
      (valid-fn {:params {}} identity) => (contains {:status 400}))
    (fact "fails with missing baz"
      (valid-fn {:params {:bar "ding"}} identity) => (contains {:status 400}))
    (fact "passes on single in map"
      (valid-fn {:params {:foo "!"}} identity) => {:foo "!"})
    (fact "passes on multiple in map"
      (valid-fn {:params {:bar "R", "baz" "Z"}} identity) => {:bar "R", :baz "Z"}))

  (facts "composing multiple and multiple"
    (def valid-fn (p/either (p/require-all [(p/string :param1) (p/string :param2)])
                            (p/require-all [(p/string :param3) (p/string :param4)])))
    (fact "fails empty params"
      (valid-fn {:params {}} identity) => (contains {:status 400}))
    (fact "fails with just param1"
      (valid-fn {:params {:param1 ""}} identity) => (contains {:status 400}))
    (fact "fails with just param2"
      (valid-fn {:params {:param2 ""}} identity) => (contains {:status 400}))
    (fact "passes with param1 and param2"
      (valid-fn {:params {:param1 "1", :param2 "2"}} identity) => {:param1 "1", :param2 "2"})
    (fact "fails with just param3"
      (valid-fn {:params {:param3 ""}} identity) => (contains {:status 400}))
    (fact "fails with just param4"
      (valid-fn {:params {:param4 ""}} identity) => (contains {:status 400}))
    (fact "passes with param3 and param4"
      (valid-fn {:params {:param3 "3", :param4 "4"}} identity) => {:param3 "3", :param4 "4"})
    (fact "fails param1 and param4"
      (valid-fn {:params {:param1 "1", :param4 "4"}} identity) => (contains {:status 400}))))

(facts "about require-any"
  (facts "about basic usage"
    (def valid-fn (p/require-any [(p/string :foo) (p/string :bar) (p/string :baz)]))
    (fact "fails with no arguments"
      (valid-fn {} identity) => (contains {:status 400}))
    (fact "passes with :foo"
      (valid-fn {:params {:foo "O"}} identity) => {:foo "O"})
    (fact "passes with :bar"
      (valid-fn {:params {:bar "R"}} identity) => {:bar "R"})
    (fact "passes with :baz"
      (valid-fn {:params {:baz "Z"}} identity) => {:baz "Z"})
    (fact "passes with multiples"
      (valid-fn {:params {:baz "Z", :foo "O"}} identity) => {:baz "Z", :foo "O"}))

  (fact "prefers to complain about params that were given"
    (def valid-fn (p/require-any [(p/string :bar) (p/string :foo)]))
    (-> (valid-fn {:params {:bar 0}} identity) :errors first :title)
    => "Expected type `string` for param `bar`"
    (-> (valid-fn {:params {:foo 0}} identity) :errors first :title)
    => "Expected type `string` for param `foo`"
    (-> (valid-fn {} identity) :errors first :title)
    => #"Expected type `string`")

  (fact "throws exception when using positional arguments in success-fn"
    (def valid-fn (p/require-any [(p/string :a) (p/string :b)]))
    (valid-fn {:params {:a "", :b ""}} (fn [a b c])) => (throws #"require-any expects a success-fn with 0 or 1 argument"))

  (fact "composes with require-all and basic"
    (def valid-fn (p/require-all [(p/string :foo)
                                  (p/require-any [(p/string :bar)
                                                  (p/string :baz)])]))
    (valid-fn {} identity) => (contains {:status 400})
    (valid-fn {:params {:foo ""}} identity) => (contains {:status 400})
    (valid-fn {:params {:foo "", :baz "Z"}} identity) => {:foo "", :baz "Z"}
    (valid-fn {:params {:foo "", :baz "Z", :bar "R"}} identity) => {:foo "", :baz "Z", :bar "R"})

  (fact "composes with just require-all"
    (def valid-fn (p/require-any [(p/require-all [(p/string :foo), (p/string :quz)])
                                  (p/require-all [(p/string :bar), (p/string :baz)])]))
    (valid-fn {:params {:foo "", :quz "Z"}} identity) => {:foo "", :quz "Z"}
    (valid-fn {:params {:bar "R", :baz "z"}} identity) => {:bar "R", :baz "z"}
    (valid-fn {:params {:foo "", :bar "R", :baz "z"}} identity) => {:bar "R", :baz "z"}
    (valid-fn {:params {:foo "", :quz "Z", :bar "R", :baz "z"}} identity) => {:bar "R", :baz "z", :foo "", :quz "Z"}))
