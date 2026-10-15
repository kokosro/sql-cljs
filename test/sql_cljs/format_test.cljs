(ns sql-cljs.format-test
  "The golden battery: every clause and operator of a query pinned to its
   exact SQL text."
  (:require [cljs.test :refer [deftest is testing]]
            [sql-cljs.core :as sql]))

(defn- text [q] (:text (sql/format q)))

;; ---------------------------------------------------------------------------
;; SELECT list
;; ---------------------------------------------------------------------------

(deftest select-list
  (testing "entities, qualified both ways, symbols"
    (is (= "SELECT a, b FROM foo" (text {:select [:a :b] :from [:foo]})))
    (is (= "SELECT foo.a, foo.b FROM foo" (text {:select [:foo/a :foo.b] :from [:foo]})))
    (is (= "SELECT a, foo.b FROM foo" (text {:select '[a foo/b] :from '[foo]}))))
  (testing "a single keyword is a one-item list"
    (is (= "SELECT a FROM t" (text {:select :a :from :t}))))
  (testing "the star element: bare, qualified, as a function argument"
    (is (= "SELECT * FROM t" (text {:select :* :from [:t]})))
    (is (= "SELECT * FROM t" (text {:select [:*] :from [:t]})))
    (is (= "SELECT t.*, u.b FROM t, u" (text {:select [:t/* :u.b] :from [:t :u]})))
    (is (= "SELECT t.* FROM t" (text {:select [:t.*] :from [:t]}))))
  (testing "aliases always use AS"
    (is (= "SELECT a AS b FROM t" (text {:select [[:a :b]] :from [:t]})))
    (is (= "SELECT t.a AS x, b FROM t" (text {:select [[:t/a :x] :b] :from [:t]})))
    (is (= "SELECT a AS b FROM t" (text {:select '[[a b]] :from [:t]}))))
  (testing "function calls nest: no alias, with alias, with arguments"
    (is (= "SELECT COUNT(*) FROM t" (text {:select [[[:count :*]]] :from [:t]})))
    (is (= "SELECT COUNT(*) AS total FROM t" (text {:select [[[:count :*] :total]] :from [:t]})))
    (is (= "SELECT COUNT(t.*) FROM t" (text {:select [[[:count :t/*]]] :from [:t]})))
    (is (= "SELECT COUNT(a), MAX(b) AS top FROM t"
           (text {:select [[[:count :a]] [[:max :b] :top]] :from [:t]})))
    (is (= "SELECT NOW()" (text {:select [[[:now]]]})))
    (is (= {:text "SELECT COALESCE(a, $1) AS a FROM t" :values ["x"]}
           (sql/format {:select [[[:coalesce :a "x"] :a]] :from [:t]})))
    (is (= "SELECT MYSCHEMA.MYFN(a) FROM t" (text {:select [[[:myschema/myfn :a]]] :from [:t]})))
    (is (= "SELECT STRING_AGG(name, $1) FROM t" (text {:select [[[:string_agg :name ", "]]] :from [:t]}))))
  (testing "nested function calls and operators inside a select item"
    (is (= "SELECT ROUND(AVG(a), $1) AS r FROM t" (text {:select [[[:round [:avg :a] 2] :r]] :from [:t]})))
    (is (= "SELECT a + b AS s FROM t" (text {:select [[[:+ :a :b] :s]] :from [:t]})))
    (is (= "SELECT (a + b) * c FROM t" (text {:select [[[:* [:+ :a :b] :c]]] :from [:t]})))
    (is (= "SELECT COUNT(*) = $1 AS one FROM t" (text {:select [[[:= [:count :*] 1] :one]] :from [:t]}))))
  (testing "a value selects only when nested as an expression"
    (is (= {:text "SELECT $1" :values [1]} (sql/format {:select [[1]]})))
    (is (= {:text "SELECT $1 AS one, a FROM t" :values [1]} (sql/format {:select [[1 :one] :a] :from [:t]}))))
  (testing "scalar subqueries, bare and aliased"
    (is (= "SELECT name, (SELECT MAX(pop) FROM cities WHERE cities.state = states.name) FROM states"
           (text {:select [:name {:select [[[:max :pop]]] :from [:cities]
                                 :where [:= :cities/state :states/name]}]
                  :from [:states]})))
    (is (= "SELECT (SELECT COUNT(*) FROM u) AS n FROM t"
           (text {:select [[{:select [[[:count :*]]] :from [:u]} :n]] :from [:t]}))))
  (testing "raw in the select list"
    (is (= "SELECT NOW() AS now FROM t" (text {:select [[(sql/raw "NOW()") :now]] :from [:t]})))
    (is (= "SELECT NOW() AS now FROM t" (text {:select [[[:raw "NOW()"] :now]] :from [:t]})))))

(deftest select-distinct
  (is (= "SELECT DISTINCT a, b FROM t" (text {:select [:a :b] :distinct true :from [:t]})))
  (is (= "SELECT a FROM t" (text {:select [:a] :distinct false :from [:t]})))
  (is (= "SELECT DISTINCT ON (location) location, time, report FROM weather_reports ORDER BY location, time DESC"
         (text {:select [:location :time :report] :distinct-on [:location]
                :from [:weather_reports] :order-by [:location [:time :desc]]})))
  (is (= "SELECT DISTINCT ON (a, b) a, b, c FROM t" (text {:select [:a :b :c] :distinct-on [:a :b] :from [:t]})))
  (is (= "SELECT DISTINCT ON (a) a FROM t" (text {:select [:a] :distinct-on :a :from [:t]})))
  (is (= "SELECT DISTINCT ON (LOWER(a)) a FROM t" (text {:select [:a] :distinct-on [[:lower :a]] :from [:t]}))))

;; ---------------------------------------------------------------------------
;; FROM and JOIN
;; ---------------------------------------------------------------------------

(deftest from-clause
  (is (= "SELECT * FROM t" (text {:select :* :from :t})))
  (is (= "SELECT * FROM t, u" (text {:select :* :from [:t :u]})))
  (is (= "SELECT * FROM public.t" (text {:select :* :from [:public/t]})))
  (is (= "SELECT * FROM t AS a, u AS b" (text {:select :* :from [[:t :a] [:u :b]]})))
  (is (= "SELECT * FROM t" (text {:select :* :from [[:t]]})))
  (is (= "SELECT * FROM (SELECT a FROM u) AS s" (text {:select :* :from [[{:select [:a] :from [:u]} :s]]})))
  (is (= "SELECT * FROM (SELECT a FROM u)" (text {:select :* :from [{:select [:a] :from [:u]}]})))
  (is (= "SELECT * FROM \"Users\" AS u" (text {:select :* :from [[(sql/raw "\"Users\"") :u]]})))
  (is (= {:text "SELECT s.a FROM (SELECT a FROM u WHERE b = $1) AS s WHERE s.a > $2" :values [1 2]}
         (sql/format {:select [:s/a] :from [[{:select [:a] :from [:u] :where [:= :b 1]} :s]]
                      :where [:> :s/a 2]}))))

(deftest joins
  (testing "every join type with ON"
    (is (= "SELECT * FROM foo INNER JOIN bar ON foo.id = bar.foo_id"
           (text {:select :* :from [:foo] :join [:bar [:= :foo/id :bar/foo_id]]})))
    (is (= "SELECT * FROM foo INNER JOIN bar ON foo.id = bar.foo_id"
           (text {:select :* :from [:foo] :inner-join [:bar [:= :foo/id :bar/foo_id]]})))
    (is (= "SELECT * FROM foo LEFT JOIN bar ON foo.id = bar.foo_id"
           (text {:select :* :from [:foo] :left-join [:bar [:= :foo/id :bar/foo_id]]})))
    (is (= "SELECT * FROM foo RIGHT JOIN bar ON foo.id = bar.foo_id"
           (text {:select :* :from [:foo] :right-join [:bar [:= :foo/id :bar/foo_id]]})))
    (is (= "SELECT * FROM foo FULL JOIN bar ON foo.id = bar.foo_id"
           (text {:select :* :from [:foo] :full-join [:bar [:= :foo/id :bar/foo_id]]}))))
  (testing "every join type with USING"
    (is (= "SELECT * FROM foo INNER JOIN bar USING (id)" (text {:select :* :from [:foo] :join [:bar [:using :id]]})))
    (is (= "SELECT * FROM foo INNER JOIN bar USING (a, b)" (text {:select :* :from [:foo] :inner-join [:bar [:using :a :b]]})))
    (is (= "SELECT * FROM foo LEFT JOIN bar USING (id)" (text {:select :* :from [:foo] :left-join [:bar [:using :id]]})))
    (is (= "SELECT * FROM foo RIGHT JOIN bar USING (id)" (text {:select :* :from [:foo] :right-join [:bar [:using :id]]})))
    (is (= "SELECT * FROM foo FULL JOIN bar USING (id)" (text {:select :* :from [:foo] :full-join [:bar [:using :id]]}))))
  (testing "CROSS JOIN takes tables only"
    (is (= "SELECT * FROM foo CROSS JOIN bar" (text {:select :* :from [:foo] :cross-join [:bar]})))
    (is (= "SELECT * FROM foo CROSS JOIN bar" (text {:select :* :from [:foo] :cross-join :bar})))
    (is (= "SELECT * FROM foo CROSS JOIN bar AS b CROSS JOIN baz"
           (text {:select :* :from [:foo] :cross-join [[:bar :b] :baz]}))))
  (testing "aliased and subquery join targets, several joins in one clause, a compound condition"
    (is (= "SELECT * FROM foo AS f INNER JOIN bar AS b ON f.id = b.foo_id INNER JOIN baz AS z ON b.id = z.bar_id"
           (text {:select :* :from [[:foo :f]]
                  :join [[:bar :b] [:= :f/id :b/foo_id]
                         [:baz :z] [:= :b/id :z/bar_id]]})))
    (is (= {:text "SELECT * FROM foo LEFT JOIN (SELECT a FROM u WHERE b = $1) AS s ON s.a = foo.a" :values [1]}
           (sql/format {:select :* :from [:foo]
                        :left-join [[{:select [:a] :from [:u] :where [:= :b 1]} :s] [:= :s/a :foo/a]]})))
    (is (= {:text "SELECT * FROM foo INNER JOIN bar ON (foo.id = bar.foo_id) AND (bar.kind = $1)" :values ["x"]}
           (sql/format {:select :* :from [:foo]
                        :join [:bar [:and [:= :foo/id :bar/foo_id] [:= :bar/kind "x"]]]})))
    (is (= "SELECT * FROM foo INNER JOIN bar ON foo.id = bar.foo_id"
           (text {:select :* :from [:foo] :join [:bar (sql/raw "foo.id = bar.foo_id")]}))))
  (testing "join kinds are emitted in a fixed order after FROM"
    (is (= "SELECT * FROM a INNER JOIN b ON a.x = b.x LEFT JOIN c ON a.y = c.y RIGHT JOIN d USING (z) FULL JOIN e USING (w) CROSS JOIN f WHERE a.x = $1"
           (text {:select :* :from [:a]
                  :cross-join [:f]
                  :full-join [:e [:using :w]]
                  :right-join [:d [:using :z]]
                  :left-join [:c [:= :a/y :c/y]]
                  :join [:b [:= :a/x :b/x]]
                  :where [:= :a/x 1]})))))

;; ---------------------------------------------------------------------------
;; WHERE and the operators
;; ---------------------------------------------------------------------------

(defn- where [expr]
  (sql/format {:select [:a] :from [:t] :where expr}))

(deftest comparison-operators
  (is (= {:text "SELECT a FROM t WHERE a = $1" :values [1]} (where [:= :a 1])))
  (is (= {:text "SELECT a FROM t WHERE a <> $1" :values [1]} (where [:not= :a 1])))
  (is (= {:text "SELECT a FROM t WHERE a <> $1" :values [1]} (where [:<> :a 1])))
  (is (= {:text "SELECT a FROM t WHERE a < $1" :values [1]} (where [:< :a 1])))
  (is (= {:text "SELECT a FROM t WHERE a > $1" :values [1]} (where [:> :a 1])))
  (is (= {:text "SELECT a FROM t WHERE a <= $1" :values [1]} (where [:<= :a 1])))
  (is (= {:text "SELECT a FROM t WHERE a >= $1" :values [1]} (where [:>= :a 1])))
  (testing "both sides are expressions: entities, values, qualified names, symbols"
    (is (= {:text "SELECT a FROM t WHERE t.a = u.a" :values []} (where [:= :t/a :u/a])))
    (is (= {:text "SELECT a FROM t WHERE $1 = a" :values [1]} (where [:= 1 :a])))
    (is (= {:text "SELECT a FROM t WHERE a = $1" :values [1]} (where '[= a 1])))))

(deftest logical-operators
  (is (= {:text "SELECT a FROM t WHERE (a = $1) AND (b = $2)" :values [1 2]}
         (where [:and [:= :a 1] [:= :b 2]])))
  (is (= {:text "SELECT a FROM t WHERE (a = $1) OR (b = $2) OR (c = $3)" :values [1 2 3]}
         (where [:or [:= :a 1] [:= :b 2] [:= :c 3]])))
  (is (= {:text "SELECT a FROM t WHERE (a = $1) AND ((b = $2) OR (c = $3))" :values [1 2 3]}
         (where [:and [:= :a 1] [:or [:= :b 2] [:= :c 3]]])))
  (testing "a single operand renders alone; a bare column is a boolean operand"
    (is (= {:text "SELECT a FROM t WHERE a = $1" :values [1]} (where [:and [:= :a 1]])))
    (is (= {:text "SELECT a FROM t WHERE active AND (a = $1)" :values [1]} (where [:and :active [:= :a 1]]))))
  (testing "NOT"
    (is (= {:text "SELECT a FROM t WHERE NOT (a = $1)" :values [1]} (where [:not [:= :a 1]])))
    (is (= {:text "SELECT a FROM t WHERE NOT active" :values []} (where [:not :active])))
    (is (= {:text "SELECT a FROM t WHERE (NOT active) AND (b = $1)" :values [1]} (where [:and [:not :active] [:= :b 1]])))
    (is (= {:text "SELECT a FROM t WHERE NOT ((a = $1) AND (b = $2))" :values [1 2]} (where [:not [:and [:= :a 1] [:= :b 2]]])))))

(deftest in-operators
  (is (= {:text "SELECT a FROM t WHERE a IN ($1, $2, $3)" :values [1 2 3]} (where [:in :a [1 2 3]])))
  (is (= {:text "SELECT a FROM t WHERE a NOT IN ($1, $2)" :values ["x" "y"]} (where [:not-in :a ["x" "y"]])))
  (is (= {:text "SELECT a FROM t WHERE a IN ($1)" :values [1]} (where [:in :a [1]])))
  (testing "a lazy sequence is a list too"
    (is (= {:text "SELECT a FROM t WHERE a IN ($1, $2)" :values [2 3]} (where [:in :a (map inc [1 2])]))))
  (testing "the list holds expressions"
    (is (= {:text "SELECT a FROM t WHERE a IN (b, (c + $1))" :values [1]} (where [:in :a [:b [:+ :c 1]]]))))
  (testing "subquery forms"
    (is (= {:text "SELECT a FROM t WHERE a IN (SELECT id FROM u WHERE k = $1)" :values ["x"]}
           (where [:in :a {:select [:id] :from [:u] :where [:= :k "x"]}])))
    (is (= {:text "SELECT a FROM t WHERE a NOT IN (SELECT id FROM u)" :values []}
           (where [:not-in :a {:select [:id] :from [:u]}])))))

(deftest like-operators
  (is (= {:text "SELECT a FROM t WHERE a LIKE $1" :values ["%x%"]} (where [:like :a "%x%"])))
  (is (= {:text "SELECT a FROM t WHERE a ILIKE $1" :values ["%x%"]} (where [:ilike :a "%x%"])))
  (is (= {:text "SELECT a FROM t WHERE a NOT LIKE $1" :values ["%x%"]} (where [:not-like :a "%x%"])))
  (is (= {:text "SELECT a FROM t WHERE a NOT ILIKE $1" :values ["%x%"]} (where [:not-ilike :a "%x%"]))))

(deftest null-tests
  (is (= {:text "SELECT a FROM t WHERE a IS NULL" :values []} (where [:is-null :a])))
  (is (= {:text "SELECT a FROM t WHERE a IS NOT NULL" :values []} (where [:is-not-null :a])))
  (is (= {:text "SELECT a FROM t WHERE (a IS NULL) OR (b IS NOT NULL)" :values []}
         (where [:or [:is-null :a] [:is-not-null :b]])))
  (testing "nil is a parameter, never rewritten to IS NULL"
    (is (= {:text "SELECT a FROM t WHERE a = $1" :values [nil]} (where [:= :a nil])))))

(deftest between-operators
  (is (= {:text "SELECT a FROM t WHERE a BETWEEN $1 AND $2" :values [1 10]} (where [:between :a 1 10])))
  (is (= {:text "SELECT a FROM t WHERE a NOT BETWEEN $1 AND $2" :values [1 10]} (where [:not-between :a 1 10])))
  (testing "operator endpoints are parenthesized"
    (is (= {:text "SELECT a FROM t WHERE a BETWEEN (b - $1) AND (b + $2)" :values [1 1]}
           (where [:between :a [:- :b 1] [:+ :b 1]])))))

(deftest exists-operators
  (is (= {:text "SELECT a FROM t WHERE EXISTS (SELECT $1 FROM u WHERE u.t_id = t.id)" :values [1]}
         (where [:exists {:select [[1]] :from [:u] :where [:= :u/t_id :t/id]}])))
  (is (= {:text "SELECT a FROM t WHERE NOT EXISTS (SELECT id FROM u)" :values []}
         (where [:not-exists {:select [:id] :from [:u]}])))
  (is (= {:text "SELECT a FROM t WHERE (a = $1) AND (EXISTS (SELECT id FROM u))" :values [1]}
         (where [:and [:= :a 1] [:exists {:select [:id] :from [:u]}]]))))

(deftest arithmetic-operators
  (is (= {:text "SELECT a FROM t WHERE (a + b) = $1" :values [3]} (where [:= [:+ :a :b] 3])))
  (is (= {:text "SELECT a FROM t WHERE (a - b) > $1" :values [0]} (where [:> [:- :a :b] 0])))
  (is (= {:text "SELECT a FROM t WHERE (a * $1) < b" :values [2]} (where [:< [:* :a 2] :b])))
  (is (= {:text "SELECT a FROM t WHERE (a / b) = $1" :values [1]} (where [:= [:/ :a :b] 1])))
  (is (= {:text "SELECT a FROM t WHERE (a % $1) = $2" :values [2 0]} (where [:= [:% :a 2] 0])))
  (testing "n-ary and nested, always parenthesized when nested"
    (is (= {:text "SELECT a FROM t WHERE (a + b + c) = $1" :values [6]} (where [:= [:+ :a :b :c] 6])))
    (is (= {:text "SELECT a FROM t WHERE ((a + b) * c) = $1" :values [6]} (where [:= [:* [:+ :a :b] :c] 6])))
    (is (= {:text "SELECT a FROM t WHERE (a + (b * c)) = $1" :values [6]} (where [:= [:+ :a [:* :b :c]] 6]))))
  (testing "unary minus"
    (is (= {:text "SELECT a FROM t WHERE -(a) = $1" :values [1]} (where [:= [:- :a] 1])))
    (is (= {:text "SELECT a FROM t WHERE -(a + b) = $1" :values [1]} (where [:= [:- [:+ :a :b]] 1])))
    (is (= {:text "SELECT a FROM t WHERE a = -($1)" :values [1]} (where [:= :a [:- 1]])))))

(deftest function-calls-in-expressions
  (is (= {:text "SELECT a FROM t WHERE LOWER(a) = $1" :values ["x"]} (where [:= [:lower :a] "x"])))
  (is (= {:text "SELECT a FROM t WHERE COALESCE(a, $1) = $2" :values [0 1]} (where [:= [:coalesce :a 0] 1])))
  (is (= {:text "SELECT a FROM t WHERE DATE_TRUNC($1, created) = $2" :values ["day" "2020-01-01"]}
         (where [:= [:date_trunc "day" :created] "2020-01-01"])))
  (testing "a function call is not parenthesized as an operand; its operator arguments are not either"
    (is (= {:text "SELECT a FROM t WHERE ABS(a - b) < $1" :values [1]} (where [:< [:abs [:- :a :b]] 1])))
    (is (= {:text "SELECT a FROM t WHERE NOT EXISTS_FN(a)" :values []} (where [:not [:exists_fn :a]]))))
  (testing "a symbol as the function name"
    (is (= {:text "SELECT a FROM t WHERE LOWER(a) = $1" :values ["x"]} (where '[= [lower a] "x"])))))

(deftest raw-in-expressions
  (is (= {:text "SELECT a FROM t WHERE created > NOW() - INTERVAL '1 day'" :values []}
         (where [:> :created (sql/raw "NOW() - INTERVAL '1 day'")])))
  (is (= {:text "SELECT a FROM t WHERE created > NOW() - INTERVAL '1 day'" :values []}
         (where [:> :created [:raw "NOW() - INTERVAL '1 day'"]])))
  (is (= {:text "SELECT a FROM t WHERE active" :values []}
         (sql/format {:select [:a] :from [:t] :where (sql/raw "active")})))
  (is (= {:text "SELECT a FROM t WHERE (a = $1) AND tsv @@ to_tsquery('x')" :values [1]}
         (where [:and [:= :a 1] (sql/raw "tsv @@ to_tsquery('x')")]))))

;; ---------------------------------------------------------------------------
;; GROUP BY, HAVING, ORDER BY, LIMIT, OFFSET
;; ---------------------------------------------------------------------------

(deftest group-by-and-having
  (is (= "SELECT a, COUNT(*) FROM t GROUP BY a" (text {:select [:a [[:count :*]]] :from [:t] :group-by [:a]})))
  (is (= "SELECT a, b FROM t GROUP BY a, b" (text {:select [:a :b] :from [:t] :group-by [:a :b]})))
  (is (= "SELECT a FROM t GROUP BY a" (text {:select [:a] :from [:t] :group-by :a})))
  (is (= "SELECT a FROM t GROUP BY t.a, LOWER(b)" (text {:select [:a] :from [:t] :group-by [:t/a [:lower :b]]})))
  (is (= {:text "SELECT a, COUNT(*) FROM t GROUP BY a HAVING COUNT(*) > $1" :values [1]}
         (sql/format {:select [:a [[:count :*]]] :from [:t] :group-by [:a] :having [:> [:count :*] 1]})))
  (is (= {:text "SELECT a FROM t WHERE b = $1 GROUP BY a HAVING (COUNT(*) > $2) AND (SUM(c) < $3)" :values [1 2 3]}
         (sql/format {:select [:a] :from [:t] :where [:= :b 1] :group-by [:a]
                      :having [:and [:> [:count :*] 2] [:< [:sum :c] 3]]})))
  (is (= "SELECT a FROM t HAVING COUNT(*) > 0"
         (text {:select [:a] :from [:t] :having (sql/raw "COUNT(*) > 0")}))))

(deftest order-by-clause
  (is (= "SELECT a FROM t ORDER BY a" (text {:select [:a] :from [:t] :order-by [:a]})))
  (is (= "SELECT a FROM t ORDER BY a" (text {:select [:a] :from [:t] :order-by :a})))
  (is (= "SELECT a FROM t ORDER BY a, b" (text {:select [:a] :from [:t] :order-by [:a :b]})))
  (is (= "SELECT a FROM t ORDER BY a ASC" (text {:select [:a] :from [:t] :order-by [[:a :asc]]})))
  (is (= "SELECT a FROM t ORDER BY a DESC" (text {:select [:a] :from [:t] :order-by [[:a :desc]]})))
  (is (= "SELECT a FROM t ORDER BY a NULLS FIRST" (text {:select [:a] :from [:t] :order-by [[:a :nulls-first]]})))
  (is (= "SELECT a FROM t ORDER BY a NULLS LAST" (text {:select [:a] :from [:t] :order-by [[:a :nulls-last]]})))
  (is (= "SELECT a FROM t ORDER BY a ASC NULLS LAST" (text {:select [:a] :from [:t] :order-by [[:a :asc :nulls-last]]})))
  (is (= "SELECT a FROM t ORDER BY a DESC NULLS FIRST" (text {:select [:a] :from [:t] :order-by [[:a :desc :nulls-first]]})))
  (is (= "SELECT a FROM t ORDER BY a DESC NULLS FIRST" (text {:select [:a] :from [:t] :order-by [[:a :nulls-first :desc]]})))
  (is (= "SELECT a FROM t ORDER BY a" (text {:select [:a] :from [:t] :order-by [[:a]]})))
  (is (= "SELECT a FROM t ORDER BY t.a DESC, b, LOWER(c) ASC"
         (text {:select [:a] :from [:t] :order-by [[:t/a :desc] :b [[:lower :c] :asc]]})))
  (is (= "SELECT a FROM t ORDER BY a + b, c" (text {:select [:a] :from [:t] :order-by [[[:+ :a :b]] :c]})))
  (is (= "SELECT a FROM t ORDER BY a ASC, b DESC" (text {:select [:a] :from [:t] :order-by '[[a asc] [b desc]]}))))

(deftest limit-and-offset
  (is (= {:text "SELECT a FROM t LIMIT $1" :values [10]} (sql/format {:select [:a] :from [:t] :limit 10})))
  (is (= {:text "SELECT a FROM t OFFSET $1" :values [20]} (sql/format {:select [:a] :from [:t] :offset 20})))
  (is (= {:text "SELECT a FROM t LIMIT $1 OFFSET $2" :values [10 20]}
         (sql/format {:select [:a] :from [:t] :offset 20 :limit 10})))
  (is (= {:text "SELECT a FROM t LIMIT ALL" :values []} (sql/format {:select [:a] :from [:t] :limit :all})))
  (is (= {:text "SELECT a FROM t WHERE a = $1 ORDER BY a LIMIT $2 OFFSET $3" :values [1 10 20]}
         (sql/format {:select [:a] :from [:t] :where [:= :a 1] :order-by [:a] :limit 10 :offset 20})))
  (is (= {:text "SELECT a FROM t LIMIT 10" :values []} (sql/format {:select [:a] :from [:t] :limit (sql/raw "10")}))))

;; ---------------------------------------------------------------------------
;; UNION, WITH, FOR
;; ---------------------------------------------------------------------------

(deftest set-operations
  (is (= {:text "(SELECT a FROM t) UNION (SELECT a FROM u)" :values []}
         (sql/format {:union [{:select [:a] :from [:t]} {:select [:a] :from [:u]}]})))
  (is (= {:text "(SELECT a FROM t) UNION ALL (SELECT a FROM u)" :values []}
         (sql/format {:union-all [{:select [:a] :from [:t]} {:select [:a] :from [:u]}]})))
  (is (= {:text "(SELECT a FROM t) UNION (SELECT a FROM u) UNION (SELECT a FROM v)" :values []}
         (sql/format {:union [{:select [:a] :from [:t]} {:select [:a] :from [:u]} {:select [:a] :from [:v]}]})))
  (testing "parameters number across operands; ORDER BY / LIMIT / OFFSET apply to the whole"
    (is (= {:text "(SELECT a FROM t WHERE b = $1) UNION ALL (SELECT a FROM u WHERE b = $2) ORDER BY a LIMIT $3 OFFSET $4"
            :values [1 2 10 5]}
           (sql/format {:union-all [{:select [:a] :from [:t] :where [:= :b 1]}
                                    {:select [:a] :from [:u] :where [:= :b 2]}]
                        :order-by [:a] :limit 10 :offset 5}))))
  (testing "an operand keeps its own ORDER BY / LIMIT inside its parentheses"
    (is (= {:text "(SELECT a FROM t ORDER BY a LIMIT $1) UNION (SELECT a FROM u)" :values [1]}
           (sql/format {:union [{:select [:a] :from [:t] :order-by [:a] :limit 1} {:select [:a] :from [:u]}]}))))
  (testing "a set operation nests as an operand and as a subquery"
    (is (= "((SELECT a FROM t) UNION (SELECT a FROM u)) UNION ALL (SELECT a FROM v)"
           (text {:union-all [{:union [{:select [:a] :from [:t]} {:select [:a] :from [:u]}]}
                              {:select [:a] :from [:v]}]})))
    (is (= "SELECT * FROM ((SELECT a FROM t) UNION (SELECT a FROM u)) AS s"
           (text {:select :* :from [[{:union [{:select [:a] :from [:t]} {:select [:a] :from [:u]}]} :s]]})))
    (is (= "SELECT a FROM t WHERE a IN ((SELECT a FROM u) UNION (SELECT a FROM v))"
           (text {:select [:a] :from [:t] :where [:in :a {:union [{:select [:a] :from [:u]} {:select [:a] :from [:v]}]}]})))))

(deftest common-table-expressions
  (is (= {:text "WITH r AS (SELECT region FROM orders WHERE amount > $1) SELECT region FROM r WHERE region = $2"
          :values [100 "eu"]}
         (sql/format {:with [[:r {:select [:region] :from [:orders] :where [:> :amount 100]}]]
                      :select [:region] :from [:r] :where [:= :region "eu"]})))
  (testing "several CTEs, column lists, parameters spanning CTEs and the main statement"
    (is (= {:text "WITH regional_sales AS (SELECT region, SUM(amount) AS total_sales FROM orders WHERE year = $1 GROUP BY region), top_regions(region) AS (SELECT region FROM regional_sales WHERE total_sales > $2) SELECT region, SUM(amount) AS product_sales FROM orders WHERE (region IN (SELECT region FROM top_regions)) AND (year = $3) GROUP BY region"
            :values [2020 1000 2020]}
           (sql/format {:with [[:regional_sales {:select [:region [[:sum :amount] :total_sales]]
                                                 :from [:orders] :where [:= :year 2020] :group-by [:region]}]
                               [[:top_regions :region] {:select [:region] :from [:regional_sales]
                                                        :where [:> :total_sales 1000]}]]
                        :select [:region [[:sum :amount] :product_sales]]
                        :from [:orders]
                        :where [:and [:in :region {:select [:region] :from [:top_regions]}] [:= :year 2020]]
                        :group-by [:region]}))))
  (testing "WITH RECURSIVE with a column list and a UNION ALL body"
    (is (= {:text "WITH RECURSIVE t(n) AS ((SELECT $1) UNION ALL (SELECT n + $2 FROM t WHERE n < $3)) SELECT SUM(n) FROM t"
            :values [1 1 100]}
           (sql/format {:with-recursive [[[:t :n] {:union-all [{:select [[1]]}
                                                                {:select [[[:+ :n 1]]] :from [:t] :where [:< :n 100]}]}]]
                        :select [[[:sum :n]]] :from [:t]}))))
  (testing "a CTE with two columns and a raw body; a CTE feeding a set operation"
    (is (= "WITH t(a, b) AS (VALUES (1, 2)) SELECT a, b FROM t"
           (text {:with [[[:t :a :b] (sql/raw "VALUES (1, 2)")]] :select [:a :b] :from [:t]})))
    (is (= "WITH x AS (SELECT a FROM t) (SELECT a FROM x) UNION (SELECT a FROM u)"
           (text {:with [[:x {:select [:a] :from [:t]}]]
                  :union [{:select [:a] :from [:x]} {:select [:a] :from [:u]}]})))))

(deftest locking-clause
  (is (= "SELECT a FROM t FOR UPDATE" (text {:select [:a] :from [:t] :for :update})))
  (is (= "SELECT a FROM t FOR UPDATE" (text {:select [:a] :from [:t] :for [:update]})))
  (is (= "SELECT a FROM t FOR SHARE" (text {:select [:a] :from [:t] :for :share})))
  (is (= "SELECT a FROM t FOR NO KEY UPDATE" (text {:select [:a] :from [:t] :for :no-key-update})))
  (is (= "SELECT a FROM t FOR KEY SHARE" (text {:select [:a] :from [:t] :for :key-share})))
  (is (= "SELECT a FROM t FOR UPDATE OF t" (text {:select [:a] :from [:t] :for [:update :t]})))
  (is (= "SELECT a FROM t, u FOR UPDATE OF t, u NOWAIT" (text {:select [:a] :from [:t :u] :for [:update :t :u :nowait]})))
  (is (= "SELECT a FROM t FOR SHARE SKIP LOCKED" (text {:select [:a] :from [:t] :for [:share :skip-locked]})))
  (is (= "SELECT a FROM t FOR NO KEY UPDATE OF t SKIP LOCKED" (text {:select [:a] :from [:t] :for [:no-key-update :t :skip-locked]})))
  (is (= "SELECT a FROM t FOR KEY SHARE OF t NOWAIT" (text {:select [:a] :from [:t] :for [:key-share :nowait :t]})))
  (is (= "SELECT a FROM t FOR UPDATE NOWAIT" (text {:select [:a] :from [:t] :for [:update :nowait]})))
  (testing "FOR comes last"
    (is (= {:text "SELECT a FROM t WHERE a = $1 ORDER BY a LIMIT $2 OFFSET $3 FOR UPDATE SKIP LOCKED" :values [1 1 0]}
           (sql/format {:select [:a] :from [:t] :where [:= :a 1] :order-by [:a] :limit 1 :offset 0
                        :for [:update :skip-locked]})))))

;; ---------------------------------------------------------------------------
;; Whole statements
;; ---------------------------------------------------------------------------

(deftest full-synopsis-order
  (is (= {:text "WITH c AS (SELECT id FROM u WHERE k = $1) SELECT DISTINCT t.a, COUNT(*) AS n FROM t AS t LEFT JOIN c ON c.id = t.c_id WHERE (t.b = $2) AND (t.d IN ($3, $4)) GROUP BY t.a HAVING COUNT(*) > $5 ORDER BY n DESC, t.a LIMIT $6 OFFSET $7 FOR UPDATE OF t"
          :values ["k" 2 3 4 5 6 7]}
         (sql/format {:with [[:c {:select [:id] :from [:u] :where [:= :k "k"]}]]
                      :select [:t/a [[:count :*] :n]]
                      :distinct true
                      :from [[:t :t]]
                      :left-join [:c [:= :c/id :t/c_id]]
                      :where [:and [:= :t/b 2] [:in :t/d [3 4]]]
                      :group-by [:t/a]
                      :having [:> [:count :*] 5]
                      :order-by [[:n :desc] :t/a]
                      :limit 6
                      :offset 7
                      :for [:update :t]}))))

(deftest nested-statements-everywhere
  (is (= {:text "SELECT (SELECT COUNT(*) FROM u WHERE u.k = $1) AS n FROM (SELECT a FROM v WHERE b = $2) AS s WHERE (EXISTS (SELECT $3 FROM w WHERE w.a = s.a)) AND (s.a NOT IN (SELECT a FROM x WHERE c = $4))"
          :values ["k" 2 1 4]}
         (sql/format {:select [[{:select [[[:count :*]]] :from [:u] :where [:= :u/k "k"]} :n]]
                      :from [[{:select [:a] :from [:v] :where [:= :b 2]} :s]]
                      :where [:and [:exists {:select [[1]] :from [:w] :where [:= :w/a :s/a]}]
                              [:not-in :s/a {:select [:a] :from [:x] :where [:= :c 4]}]]})))
  (testing "the text is one line, no trailing semicolon, single spaces"
    (let [t (text {:select [:a] :from [:t] :where [:= :a 1]})]
      (is (not (re-find #"\n|;|  " t))))))
