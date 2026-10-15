(ns sql-cljs.values-test
  "The values battery: $N order, untouched pass-through, inline mode."
  (:require [cljs.test :refer [deftest is testing]]
            [goog.object :as gobj]
            [sql-cljs.core :as sql]))

(defn- thrown-data [f]
  (try (f) nil (catch :default e (ex-data e))))

;; ClojureScript has no BigInt literal (1N reads as a plain number); the
;; constructor is fetched from the global object by name, here only.
(def ^:private big
  ((gobj/get goog/global "BigInt") "12345678901234567890"))

(deftype Opaque [])

;; ---------------------------------------------------------------------------
;; $N numbering
;; ---------------------------------------------------------------------------

(deftest values-follow-textual-order
  (testing "left to right across clauses"
    (is (= {:text "SELECT a FROM t WHERE (a = $1) AND (b IN ($2, $3)) LIMIT $4 OFFSET $5"
            :values ["x" 1 2 10 20]}
           (sql/format {:select [:a] :from [:t] :where [:and [:= :a "x"] [:in :b [1 2]]]
                        :limit 10 :offset 20}))))
  (testing "a repeated value is collected twice"
    (is (= {:text "SELECT a FROM t WHERE (a = $1) OR (b = $2)" :values ["x" "x"]}
           (sql/format {:select [:a] :from [:t] :where [:or [:= :a "x"] [:= :b "x"]]}))))
  (testing "a value in the select list comes before one in WHERE, a CTE's before both, a subquery's in place"
    (is (= {:text "WITH c AS (SELECT a FROM u WHERE k = $1) SELECT $2 AS one, (SELECT b FROM v WHERE m = $3) AS s FROM c WHERE a = $4"
            :values ["cte" 1 "sub" "main"]}
           (sql/format {:with [[:c {:select [:a] :from [:u] :where [:= :k "cte"]}]]
                        :select [[1 :one] [{:select [:b] :from [:v] :where [:= :m "sub"]} :s]]
                        :from [:c]
                        :where [:= :a "main"]}))))
  (testing "DISTINCT ON expressions come before the select list, join conditions before WHERE"
    (is (= {:text "SELECT DISTINCT ON (COALESCE(a, $1)) COALESCE(b, $2) FROM t INNER JOIN u ON u.k = $3 WHERE c = $4"
            :values [1 2 3 4]}
           (sql/format {:select [[[:coalesce :b 2]]] :distinct-on [[:coalesce :a 1]] :from [:t]
                        :join [:u [:= :u/k 3]] :where [:= :c 4]})))))

;; ---------------------------------------------------------------------------
;; Pass-through
;; ---------------------------------------------------------------------------

(deftest values-pass-through-untouched
  (let [obj (Opaque.)
        f (fn [] 1)
        q {:select [:a] :from [:t]
           :where [:and [:= :a nil] [:= :b "it's"] [:= :c 1.5] [:= :d true] [:= :e false]
                   [:= :f big] [:= :g obj] [:= :h f] [:= :i -0] [:= :j ##NaN] [:= :k ##Inf]]}
        {:keys [text values]} (sql/format q)]
    (is (= "SELECT a FROM t WHERE (a = $1) AND (b = $2) AND (c = $3) AND (d = $4) AND (e = $5) AND (f = $6) AND (g = $7) AND (h = $8) AND (i = $9) AND (j = $10) AND (k = $11)"
           text))
    (is (= 11 (count values)))
    (is (nil? (nth values 0)))
    (is (= "it's" (nth values 1)))
    (is (= 1.5 (nth values 2)))
    (is (true? (nth values 3)))
    (is (false? (nth values 4)))
    (is (= big (nth values 5)))
    (is (identical? obj (nth values 6)))
    (is (identical? f (nth values 7)))
    (is (= 0 (nth values 8)))
    (is (NaN? (nth values 9)))
    (is (= ##Inf (nth values 10)))
    (testing "the string is the same object, not a copy or a quoted spelling"
      (let [s "a 'quoted' value"]
        (is (identical? s (first (:values (sql/format {:select [:a] :from [:t] :where [:= :a s]})))))))))

;; ---------------------------------------------------------------------------
;; Inline mode
;; ---------------------------------------------------------------------------

(deftest inline-mode
  (testing "the same statement, every value as a literal, :values empty"
    (is (= {:text "SELECT a FROM t WHERE (a = NULL) AND (b = 'it''s') AND (c = 1.5) AND (d = TRUE) AND (e = FALSE) AND (f = 12345678901234567890) AND (g = 0) AND (h = -3) AND (i = '')"
            :values []}
           (sql/format {:select [:a] :from [:t]
                        :where [:and [:= :a nil] [:= :b "it's"] [:= :c 1.5] [:= :d true] [:= :e false]
                                [:= :f big] [:= :g -0] [:= :h -3] [:= :i ""]]}
                       {:inline true}))))
  (testing "quotes are doubled, nothing else is touched"
    (is (= "SELECT a FROM t WHERE a = 'Dianne''s ''horse'' \\ %'"
           (:text (sql/format {:select [:a] :from [:t] :where [:= :a "Dianne's 'horse' \\ %"]} {:inline true})))))
  (testing "every value position inlines: lists, LIMIT / OFFSET, select items, CTEs, subqueries"
    (is (= {:text "WITH c AS (SELECT a FROM u WHERE k = 'cte') SELECT 1 AS one FROM c WHERE (a IN ('x', 2, NULL)) AND (b IN (SELECT b FROM v WHERE m = TRUE)) LIMIT 10 OFFSET 5"
            :values []}
           (sql/format {:with [[:c {:select [:a] :from [:u] :where [:= :k "cte"]}]]
                        :select [[1 :one]] :from [:c]
                        :where [:and [:in :a ["x" 2 nil]] [:in :b {:select [:b] :from [:v] :where [:= :m true]}]]
                        :limit 10 :offset 5}
                       {:inline true}))))
  (testing "NaN and infinities are a format-error with :op :inline"
    (doseq [x [##NaN ##Inf ##-Inf]]
      (let [d (thrown-data #(sql/format {:select [:a] :from [:t] :where [:= :a x]} {:inline true}))]
        (is (= :sql-cljs/format-error (:type d)) (str x))
        (is (= :inline (:op d)) (str x))
        (is (if (NaN? x) (NaN? (:arg d)) (= x (:arg d))) (str x)))))
  (testing "a value without a literal spelling is a format-error naming it"
    (let [obj (Opaque.)
          d (thrown-data #(sql/format {:select [:a] :from [:t] :where [:= :a obj]} {:inline true}))]
      (is (= :inline (:op d)))
      (is (identical? obj (:arg d)))
      (is (re-find #"no SQL literal spelling" (:message d))))
    (let [f (fn [] 1)]
      (is (identical? f (:arg (thrown-data #(sql/format {:select [:a] :where [:= :a f]} {:inline true})))))))
  (testing "parameter mode never inspects a value: the same statement formats without :inline"
    (let [obj (Opaque.)]
      (is (= {:text "SELECT a FROM t WHERE a = $1" :values [obj]}
             (sql/format {:select [:a] :from [:t] :where [:= :a obj]}))))))
