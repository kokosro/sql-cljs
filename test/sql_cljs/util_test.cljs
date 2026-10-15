(ns sql-cljs.util-test
  "The engine internals: the error constructor, the entity validator, the
   raw fragment and the inline literal renderer."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [sql-cljs.util :as util]))

(defn- thrown-data [f]
  (try (f) nil (catch :default e (ex-data e))))

;; A BigInt for the literal tests. ClojureScript has no BigInt literal (1N
;; reads as a plain number), so the constructor is fetched from the global
;; object by name; this is the only place the suite reaches the host.
(def ^:private big
  ((gobj/get goog/global "BigInt") "12345678901234567890"))

(deftest format-error-shape
  (let [e (util/format-error :where [:= :a] "the message" {:allowed [:x]})]
    (is (= "sql-cljs: the message" (ex-message e)))
    (is (= {:type :sql-cljs/format-error :message "the message" :op :where :arg [:= :a] :allowed [:x]}
           (ex-data e))))
  (testing "data is optional"
    (is (= {:type :sql-cljs/format-error :message "m" :op :select :arg "x"}
           (ex-data (util/format-error :select "x" "m"))))))

(deftest entities
  (testing "entity? is keyword or symbol, never a string"
    (is (util/entity? :a))
    (is (util/entity? 'a))
    (is (not (util/entity? "a")))
    (is (not (util/entity? nil))))
  (testing "parts split on . and /"
    (is (= ["foo"] (util/entity-parts :foo)))
    (is (= ["foo" "bar"] (util/entity-parts :foo/bar)))
    (is (= ["foo" "bar"] (util/entity-parts :foo.bar)))
    (is (= ["a" "b" "c"] (util/entity-parts :a.b/c)))
    (is (= ["foo" "*"] (util/entity-parts :foo/*)))
    (is (= ["foo" ""] (util/entity-parts :foo.)))
    (is (= ["foo" "bar"] (util/entity-parts 'foo/bar))))
  (testing "star?"
    (is (util/star? :*))
    (is (util/star? :t/*))
    (is (util/star? :t.*))
    (is (not (util/star? :a)))
    (is (not (util/star? "*"))))
  (testing "entity-name renders bare, dotted"
    (is (= "foo" (util/entity-name :select :foo false)))
    (is (= "foo.bar" (util/entity-name :select :foo/bar false)))
    (is (= "foo.bar" (util/entity-name :select :foo.bar false)))
    (is (= "foo.bar" (util/entity-name :select 'foo/bar false)))
    (is (= "a_b$1" (util/entity-name :select :a_b$1 false)))
    (is (= "_x" (util/entity-name :select :_x false))))
  (testing "the star only where allowed"
    (is (= "*" (util/entity-name :select :* true)))
    (is (= "t.*" (util/entity-name :select :t/* true)))
    (let [d (thrown-data #(util/entity-name :from :* false))]
      (is (= :sql-cljs/format-error (:type d)))
      (is (= :from (:op d)))
      (is (= "*" (:arg d)))
      (is (= :* (:entity d))))
    (testing "a star that is not the last part is never accepted"
      (is (= "*" (:arg (thrown-data #(util/entity-name :select :*.a true)))))))
  (testing "a part outside [a-z_][a-z0-9$]* is a format-error naming the part, the entity and the hatch"
    (doseq [[entity part] [[:UserId "UserId"]
                           [:foo/BarBaz "BarBaz"]
                           [:a-b "a-b"]
                           [:a.1b "1b"]
                           [(keyword "ab c") "ab c"]
                           [:foo. ""]
                           [(keyword "") ""]
                           [:a.b.C "C"]
                           ['Foo "Foo"]]]
      (let [d (thrown-data #(util/entity-name :select entity false))]
        (is (= :sql-cljs/format-error (:type d)) (pr-str entity))
        (is (= :select (:op d)) (pr-str entity))
        (is (= part (:arg d)) (pr-str entity))
        (is (= entity (:entity d)) (pr-str entity))
        (is (re-find #"raw" (:message d)) (pr-str entity)))))
  (testing "a non-entity is a format-error with :arg the value"
    (let [d (thrown-data #(util/entity-name :from "foo" false))]
      (is (= :from (:op d)))
      (is (= "foo" (:arg d)))
      (is (re-find #"strings are values" (:message d))))
    (is (= 5 (:arg (thrown-data #(util/entity-name :from 5 false)))))
    (is (= nil (:arg (thrown-data #(util/entity-name :from nil false))))))
  (testing "operator-shaped? is a name without letters"
    (is (util/operator-shaped? :!=))
    (is (util/operator-shaped? :<=>))
    (is (util/operator-shaped? :||))
    (is (not (util/operator-shaped? :not=)))
    (is (not (util/operator-shaped? :count)))))

(deftest raw-fragments
  (is (util/raw? (util/make-raw :raw "x")))
  (is (= "x" (util/raw-sql (util/make-raw :raw "x"))))
  (is (= (util/make-raw :raw "x") (util/make-raw :raw "x")))
  (is (= (hash (util/make-raw :raw "x")) (hash (util/make-raw :raw "x"))))
  (is (not (util/raw? {:sql "x"})))
  (let [d (thrown-data #(util/make-raw :raw :x))]
    (is (= :raw (:op d)))
    (is (= :x (:arg d)))))

(deftest literals
  (testing "nil, strings, booleans"
    (is (= "NULL" (util/literal nil)))
    (is (= "'baz'" (util/literal "baz")))
    (is (= "''" (util/literal "")))
    (is (= "'Dianne''s horse'" (util/literal "Dianne's horse")))
    (is (= "''''''" (util/literal "''")))
    (is (= "'a\\b'" (util/literal "a\\b")))
    (is (= "TRUE" (util/literal true)))
    (is (= "FALSE" (util/literal false))))
  (testing "numbers via their decimal text"
    (is (= "42" (util/literal 42)))
    (is (= "-3" (util/literal -3)))
    (is (= "1.5" (util/literal 1.5)))
    (is (= "0" (util/literal 0)))
    (is (= "0" (util/literal -0)))
    (is (= "1e+21" (util/literal 1e21))))
  (testing "a BigInt via its decimal text"
    (is (= "12345678901234567890" (util/literal big))))
  (testing "NaN and infinities are not numeric constants"
    (doseq [x [##NaN ##Inf ##-Inf]]
      (let [d (thrown-data #(util/literal x))]
        (is (= :sql-cljs/format-error (:type d)) (str x))
        (is (= :inline (:op d)) (str x))
        (is (if (NaN? x) (NaN? (:arg d)) (= x (:arg d))) (str x)))))
  (testing "anything else has no literal spelling"
    (doseq [x [(fn [] 1) (util/make-raw :raw "x") :kw 'sym [1] {:a 1}]]
      (let [d (thrown-data #(util/literal x))]
        (is (= :inline (:op d)) (pr-str x))
        (is (= x (:arg d)) (pr-str x))))))
