(ns sql-cljs.core
  "The sql-cljs query builder for PostgreSQL: plain ClojureScript data in, a
   parameterized SQL query out. A statement is a map of clauses
   ({:select [:a :b] :from [:foo] :where [:= :foo.a \"baz\"]}), an expression
   is a vector whose first element is the operator or function, keywords and
   symbols name tables and columns, and strings are always values. `format`
   renders such a map to {:text \"SELECT a, b FROM foo WHERE foo.a = $1\"
   :values [\"baz\"]}, the exact shape pg-cljs.core/query! takes, with the
   values passed through untouched. The library is pure formatting: no JS
   interop, no runtime npm dependency, no network.

   The shapes, by clause (every clause is optional except the statement
   head, and the map's key order never matters; the text follows the
   PostgreSQL synopsis order):

   - :select, a vector of items (or a single keyword): :a, :t/a or :t.a
     (table-qualified), :* or :t/*, [expr] (any expression, no alias),
     [expr :alias] (always rendered with AS), a function call nested as
     [[:count :*]] or [[:count :*] :total], a statement map (a scalar
     subquery). :distinct true adds DISTINCT, :distinct-on [:a :b] adds
     DISTINCT ON (a, b).
   - :from, a vector of :t, [:t :alias], {subquery}, [{subquery} :alias].
   - :join / :inner-join (INNER JOIN), :left-join, :right-join, :full-join:
     a flat vector of table then condition pairs, [:bar [:= :foo.id :bar.foo_id]
     [:baz :b] [:using :id]]; the condition is an expression (ON ...) or
     [:using :col ...]. :cross-join takes tables only. Join kinds are emitted
     in that fixed order, each kind in the caller's order.
   - :where and :having, one expression vector ([:and ...] combines several).
   - :group-by, a vector of expressions. :order-by, a vector of :a or
     [expr :asc|:desc :nulls-first|:nulls-last].
   - :limit (a number or :all) and :offset (a number); the number travels as
     a parameter.
   - :union / :union-all, a vector of statement maps, each operand
     parenthesized: (SELECT ...) UNION (SELECT ...); :order-by, :limit and
     :offset next to it apply to the whole.
   - :with / :with-recursive, a vector of [:name {statement}] or
     [[:name :col ...] {statement}].
   - :for, :update or [:update :t1 :t2 :nowait]; strengths :update,
     :no-key-update, :share, :key-share; options :nowait or :skip-locked.

   Expressions: [:= a b] [:not= a b] (<>) [:<> a b] [:< a b] [:> a b]
   [:<= a b] [:>= a b], [:and ...] [:or ...] [:not x], [:+ ...] [:- ...]
   [:* ...] [:/ ...] [:% ...] ([:- x] alone is unary minus), [:in x [...]]
   [:in x {subquery}] [:not-in ...], [:like a b] [:ilike a b] [:not-like ...]
   [:not-ilike ...], [:between x lo hi] [:not-between ...], [:is-null x]
   [:is-not-null x], [:exists {subquery}] [:not-exists {subquery}], any other
   lettered keyword is a function call ([:coalesce :a 0] is COALESCE(a, $1),
   [:now] is NOW()), [:raw \"...\"] or (raw \"...\") splices SQL verbatim.
   Every nested operator expression is parenthesized, so precedence is
   never relied on."
  (:require [clojure.string :as str]
            [sql-cljs.util :as u]))

(def api-version
  "The library's own version marker, as a string. It names the version of
   the sql-cljs public surface."
  "0.1.0")

(defn raw
  "A fragment of SQL spliced into the output verbatim, wherever an
   expression, a table, a condition, a statement operand or a CTE body is
   expected: (raw \"NOW()\"), (raw \"\\\"UserId\\\"\") for an identifier the
   bare-name rule rejects. The text is copied as is: it is not validated,
   not parameterized and not quoted, so never build it from untrusted
   input. [:raw \"...\"] in expression position renders identically; at the
   top level of :select or :from, where a two-element vector is an alias
   pair, it nests like a function call: [[:raw \"NOW()\"]]. Anything but a
   string is a format-error (:op :raw)."
  [sql]
  (u/make-raw :raw sql))

;; ---------------------------------------------------------------------------
;; Rendering context: the inline switch and the parameter accumulator.
;; Text is produced strictly left to right, so the order of :values is the
;; order of $N in the text, across nested statements and CTEs.
;; ---------------------------------------------------------------------------

(defn- context
  [opts]
  {:inline? (true? (:inline opts))
   :params (volatile! [])})

(defn- value!
  "The placeholder of the value v ($N, v appended to the parameters) or,
   in inline mode, its literal."
  [ctx _op v]
  (if (:inline? ctx)
    (u/literal v)
    (let [params (:params ctx)]
      (vswap! params conj v)
      (str "$" (count @params)))))

(defn- collection-in-expression?
  "A ClojureScript collection that is neither a vector (an expression) nor
   a map (a subquery): a list, a lazy seq, a set. Never a value."
  [x]
  (and (coll? x) (not (vector? x)) (not (map? x))))

(declare statement operators)

(defn- kw
  "A symbol spelling of a DSL key (operator, modifier, option) as its keyword."
  [x]
  (if (symbol? x) (keyword x) x))

;; ---------------------------------------------------------------------------
;; Expressions
;; ---------------------------------------------------------------------------

(defn- operator-form?
  [x]
  (and (vector? x) (contains? operators (kw (first x)))))

(defn- unary-minus-form?
  [x]
  (and (vector? x) (= :- (kw (first x))) (= 2 (count x))))

(defn- raw-form
  [form]
  (if (and (= 2 (count form)) (string? (second form)))
    (second form)
    (throw (u/format-error :raw form
                           (str "[:raw ...] takes exactly one string, the SQL text to splice verbatim; got "
                                (pr-str form))))))

(declare expression)

(defn- function-call
  [ctx op k args]
  (let [fname (str/upper-case (u/entity-name op k false))
        rendered (mapv #(expression ctx k % true) args)]
    (str fname "(" (str/join ", " rendered) ")")))

(defn- vector-expression
  [ctx op form]
  (let [k (kw (first form))]
    (cond
      (empty? form)
      (throw (u/format-error op form "an empty vector is not an expression"))

      (not (u/entity? k))
      (throw (u/format-error op form
                             (str "an expression vector starts with an operator or function keyword,"
                                  " e.g. [:= :a 1] or [:count :*]; several predicates combine with"
                                  " [:and ...]; got " (pr-str form))))

      (= :raw k)
      (raw-form form)

      (contains? operators k)
      ((get operators k) ctx form)

      (u/operator-shaped? k)
      (throw (u/format-error k form
                             (str "unknown operator " (pr-str k)
                                  "; the operators are listed under :allowed, anything else is spelled"
                                  " with the raw escape hatch")
                             {:allowed (vec (sort (keys operators)))}))

      :else
      (function-call ctx op k (rest form)))))

(defn- expression
  "The SQL text of x in expression position, with :op op for errors. star?
   admits the star element (:*, :t/*)."
  ([ctx op x] (expression ctx op x false))
  ([ctx op x star?]
   (cond
     (u/raw? x) (u/raw-sql x)
     (u/entity? x) (u/entity-name op x star?)
     (map? x) (str "(" (statement ctx x) ")")
     (vector? x) (vector-expression ctx op x)
     (collection-in-expression? x)
     (throw (u/format-error op x
                            (str (pr-str x) " is neither a value nor an expression: expressions are"
                                 " vectors, subqueries are maps, and a list or set cannot travel as"
                                 " a parameter (use vec)")))
     :else (value! ctx op x))))

(defn- operand
  "An operand of an operator: a nested operator expression is parenthesized
   (unary minus, -(x), already is)."
  [ctx op x]
  (let [text (expression ctx op x)]
    (if (and (operator-form? x) (not (unary-minus-form? x)))
      (str "(" text ")")
      text)))

(defn- arity!
  [form n]
  (when-not (= n (count (rest form)))
    (throw (u/format-error (first form) form
                           (str (pr-str (first form)) " takes exactly " n
                                (if (= 1 n) " operand" " operands") ", got "
                                (count (rest form)) " in " (pr-str form))))))

(defn- binary
  [sql]
  (fn [ctx form]
    (arity! form 2)
    (let [op (first form) [a b] (rest form)]
      (str (operand ctx op a) " " sql " " (operand ctx op b)))))

(defn- logical
  [sql]
  (fn [ctx form]
    (let [op (first form) args (rest form)]
      (when (empty? args)
        (throw (u/format-error op form (str (pr-str op) " needs at least one operand"))))
      (if (= 1 (count args))
        (expression ctx op (first args))
        (str/join (str " " sql " ") (mapv #(operand ctx op %) args))))))

(defn- not-op
  [ctx form]
  (arity! form 1)
  (str "NOT " (operand ctx :not (second form))))

(defn- arithmetic
  [sql]
  (fn [ctx form]
    (let [op (first form) args (rest form)]
      (when (< (count args) 2)
        (throw (u/format-error op form (str (pr-str op) " needs at least two operands, got " (pr-str form)))))
      (str/join (str " " sql " ") (mapv #(operand ctx op %) args)))))

(defn- minus-op
  [ctx form]
  (if (= 1 (count (rest form)))
    (str "-(" (expression ctx :- (second form)) ")")
    ((arithmetic "-") ctx form)))

(defn- in-op
  [sql]
  (fn [ctx form]
    (arity! form 2)
    (let [op (first form) [a xs] (rest form)
          lhs (operand ctx op a)]
      (cond
        (map? xs) (str lhs " " sql " " (expression ctx op xs))
        (u/raw? xs) (str lhs " " sql " " (u/raw-sql xs))
        (sequential? xs)
        (if (empty? xs)
          (throw (u/format-error op form
                                 (str (pr-str op) " with an empty list is not SQL (IN () is a syntax"
                                      " error); give a non-empty list, or write FALSE (TRUE for"
                                      " :not-in) explicitly")))
          (str lhs " " sql " (" (str/join ", " (mapv #(operand ctx op %) xs)) ")"))
        :else
        (throw (u/format-error op form
                               (str "the right-hand side of " (pr-str op)
                                    " is a vector of values or a statement map, got " (pr-str xs))))))))

(defn- between-op
  [sql]
  (fn [ctx form]
    (arity! form 3)
    (let [op (first form) [a lo hi] (rest form)]
      (str (operand ctx op a) " " sql " " (operand ctx op lo) " AND " (operand ctx op hi)))))

(defn- postfix
  [sql]
  (fn [ctx form]
    (arity! form 1)
    (str (operand ctx (first form) (second form)) " " sql)))

(defn- exists-op
  [sql]
  (fn [ctx form]
    (arity! form 1)
    (let [op (first form) q (second form)]
      (cond
        (map? q) (str sql " " (expression ctx op q))
        (u/raw? q) (str sql " " (u/raw-sql q))
        :else (throw (u/format-error op form
                                     (str (pr-str op) " takes a statement map (a subquery), got "
                                          (pr-str q))))))))

(def ^:private operators
  {:= (binary "=")
   :<> (binary "<>")
   :not= (binary "<>")
   :< (binary "<")
   :> (binary ">")
   :<= (binary "<=")
   :>= (binary ">=")
   :and (logical "AND")
   :or (logical "OR")
   :not not-op
   :+ (arithmetic "+")
   :- minus-op
   :* (arithmetic "*")
   :/ (arithmetic "/")
   :% (arithmetic "%")
   :in (in-op "IN")
   :not-in (in-op "NOT IN")
   :like (binary "LIKE")
   :ilike (binary "ILIKE")
   :not-like (binary "NOT LIKE")
   :not-ilike (binary "NOT ILIKE")
   :between (between-op "BETWEEN")
   :not-between (between-op "NOT BETWEEN")
   :is-null (postfix "IS NULL")
   :is-not-null (postfix "IS NOT NULL")
   :exists (exists-op "EXISTS")
   :not-exists (exists-op "NOT EXISTS")})

;; ---------------------------------------------------------------------------
;; Shared clause pieces
;; ---------------------------------------------------------------------------

(defn- list-of
  "The items of a list clause: a vector (any sequential) or a single
   entity; empty or anything else is a format-error."
  [op x]
  (cond
    (u/entity? x) [x]
    (sequential? x) (if (seq x)
                      (vec x)
                      (throw (u/format-error op x (str (pr-str op) " must not be empty"))))
    :else (throw (u/format-error op x
                                 (str (pr-str op) " takes a vector of items, got " (pr-str x)
                                      (when (string? x) " (strings are values, never names)"))))))

(defn- alias-name
  [op a & [head]]
  (cond
    (not (u/entity? a))
    (throw (u/format-error op a
                           (str "the second element of a pair is the alias and must be a keyword"
                                " or symbol, got " (pr-str a) "; a function call is nested:"
                                " [[:count :*]] without an alias, [[:count :*] :total] with one"
                                (when (= :raw (kw head))
                                  (str "; raw SQL is nested the same way, [[:raw " (pr-str a)
                                       "]], or written (raw " (pr-str a) ")")))))

    (u/star? a)
    (throw (u/format-error op "*"
                           (str "the star is not an alias: a two-element vector is [expression alias],"
                                " so write a function call nested, [[:count :*]] or"
                                " [[:count :*] :total]")
                           {:entity a}))

    :else (u/entity-name op a false)))

(defn- table-base
  [ctx op x]
  (cond
    (u/entity? x) (u/entity-name op x false)
    (u/raw? x) (u/raw-sql x)
    (map? x) (str "(" (statement ctx x) ")")
    :else (throw (u/format-error op x
                                 (str "a table is a keyword, a statement map (a subquery) or a raw"
                                      " fragment, got " (pr-str x)
                                      (when (string? x) " (strings are values, never names)"))))))

(defn- table-ref
  "A FROM item or join target: t, [t], [t :alias]."
  [ctx op x]
  (if (vector? x)
    (case (count x)
      1 (table-base ctx op (first x))
      2 (str (table-base ctx op (first x)) " AS " (alias-name op (second x) (first x)))
      (throw (u/format-error op x
                             (str "a table vector is [table] or [table alias], got " (pr-str x)))))
    (table-base ctx op x)))

(defn- condition
  "A clause whose value is one expression: WHERE, HAVING."
  [word]
  (fn [ctx m k]
    (let [c (get m k)]
      (cond
        (u/raw? c) (str word " " (u/raw-sql c))
        (vector? c) (str word " " (expression ctx k c))
        :else
        (throw (u/format-error k c
                               (str (pr-str k) " must be an expression vector such as [:= :a 1], got "
                                    (pr-str c)
                                    (when (u/entity? c)
                                      (str "; a bare column is not a condition: write [:= " c
                                           " true] or (raw \"" (name c) "\")")))))))))

;; ---------------------------------------------------------------------------
;; Clause emitters, each (fn [ctx statement-map clause-key]) -> text
;; ---------------------------------------------------------------------------

(defn- select-item
  [ctx x]
  (cond
    (vector? x)
    (case (count x)
      1 (expression ctx :select (first x) true)
      2 (str (expression ctx :select (first x) true) " AS " (alias-name :select (second x) (first x)))
      (throw (u/format-error :select x
                             (str "a vector in :select is [expression] or [expression alias], got "
                                  (pr-str x) "; a function call is nested: [[:count :*]] or"
                                  " [[:count :*] :total]"))))

    (or (u/entity? x) (u/raw? x) (map? x))
    (expression ctx :select x true)

    (collection-in-expression? x)
    (expression ctx :select x true)

    :else
    (throw (u/format-error :select x
                           (str (pr-str x) " is a value, not a column"
                                (when (string? x) " (strings are values, never names)")
                                "; to select a value as a parameter write it nested, [["
                                (pr-str x) "]], or use the raw escape hatch")))))

(defn- emit-select
  [ctx m _]
  (when (and (contains? m :distinct) (contains? m :distinct-on))
    (throw (u/format-error :select (select-keys m [:distinct :distinct-on])
                           ":distinct and :distinct-on cannot both be given")))
  (let [modifier (cond
                   (contains? m :distinct-on)
                   (str "DISTINCT ON ("
                        (str/join ", " (mapv #(expression ctx :distinct-on %)
                                             (list-of :distinct-on (:distinct-on m))))
                        ") ")

                   (contains? m :distinct)
                   (case (:distinct m)
                     true "DISTINCT "
                     false ""
                     (throw (u/format-error :distinct (:distinct m)
                                            (str ":distinct must be true or false, got "
                                                 (pr-str (:distinct m))))))

                   :else "")
        items (mapv #(select-item ctx %) (list-of :select (:select m)))]
    (str "SELECT " modifier (str/join ", " items))))

(defn- emit-from
  [ctx m k]
  (str "FROM " (str/join ", " (mapv #(table-ref ctx k %) (list-of k (get m k))))))

(defn- join-condition
  [ctx op c]
  (cond
    (u/raw? c) (str "ON " (u/raw-sql c))

    (and (vector? c) (= :using (kw (first c))))
    (let [cols (rest c)]
      (when (empty? cols)
        (throw (u/format-error op c "[:using ...] needs at least one column")))
      (str "USING (" (str/join ", " (mapv #(u/entity-name op % false) cols)) ")"))

    (vector? c) (str "ON " (expression ctx op c))

    :else
    (throw (u/format-error op c
                           (str "a join condition is an expression vector ([:= :a.id :b.a_id]) or"
                                " [:using :col ...], got " (pr-str c))))))

(defn- emit-join
  [word]
  (fn [ctx m k]
    (let [items (list-of k (get m k))]
      (when (odd? (count items))
        (throw (u/format-error k (get m k)
                               (str (pr-str k) " is a flat vector of table then condition pairs,"
                                    " [:bar [:= :foo.id :bar.foo_id]]; got an odd count of "
                                    (count items)))))
      (str/join " " (mapv (fn [[t c]]
                            (str word " " (table-ref ctx k t) " " (join-condition ctx k c)))
                          (partition 2 items))))))

(defn- emit-cross-join
  [ctx m k]
  (str/join " " (mapv (fn [t]
                        (when (and (vector? t)
                                   (or (= :using (kw (first t))) (operator-form? t)))
                          (throw (u/format-error k t
                                                 (str "CROSS JOIN takes no condition; :cross-join is"
                                                      " a vector of tables, got " (pr-str t)))))
                        (str "CROSS JOIN " (table-ref ctx k t)))
                      (list-of k (get m k)))))

(defn- emit-group-by
  [ctx m k]
  (str "GROUP BY " (str/join ", " (mapv #(expression ctx k %) (list-of k (get m k))))))

(def ^:private directions {:asc "ASC" :desc "DESC"})
(def ^:private nulls-orders {:nulls-first "NULLS FIRST" :nulls-last "NULLS LAST"})
(def ^:private order-modifiers [:asc :desc :nulls-first :nulls-last])

(defn- order-modifier
  [x acc md0]
  (let [md (kw md0)]
    (cond
      (contains? directions md)
      (if (:dir acc)
        (throw (u/format-error :order-by x "only one of :asc / :desc per item"))
        (assoc acc :dir (get directions md)))

      (contains? nulls-orders md)
      (if (:nulls acc)
        (throw (u/format-error :order-by x "only one of :nulls-first / :nulls-last per item"))
        (assoc acc :nulls (get nulls-orders md)))

      :else
      (throw (u/format-error :order-by x
                             (str "an :order-by vector is [expression & modifiers] with"
                                  " modifiers among :asc :desc :nulls-first :nulls-last;"
                                  " an expression with its own arguments is nested,"
                                  " [[:lower :name] :asc]; got " (pr-str x))
                             {:allowed order-modifiers})))))

(defn- order-item
  [ctx x]
  (cond
    (and (u/entity? x) (or (contains? directions (kw x)) (contains? nulls-orders (kw x))))
    (throw (u/format-error :order-by x
                           (str (pr-str x) " is a sort modifier, not a column: an :order-by item"
                                " with modifiers is a vector, [[:a :desc]]")))

    (vector? x)
    (do
      (when (empty? x)
        (throw (u/format-error :order-by x "an empty vector is not an :order-by item")))
      (let [text (expression ctx :order-by (first x))
            {:keys [dir nulls]} (reduce #(order-modifier x %1 %2) {} (rest x))]
        (str text (when dir (str " " dir)) (when nulls (str " " nulls)))))

    :else (expression ctx :order-by x)))

(defn- emit-order-by
  [ctx m k]
  (str "ORDER BY " (str/join ", " (mapv #(order-item ctx %) (list-of k (get m k))))))

(defn- emit-count
  [word all?]
  (fn [ctx m k]
    (let [v (get m k)]
      (cond
        (and all? (= :all (kw v))) (str word " ALL")
        (u/raw? v) (str word " " (u/raw-sql v))
        (or (u/entity? v) (coll? v))
        (throw (u/format-error k v
                               (str (pr-str k) " takes a number" (when all? " (or :all)") ", got "
                                    (pr-str v))))
        :else (str word " " (value! ctx k v))))))

(defn- emit-union
  [word]
  (fn [ctx m k]
    (let [operands (list-of k (get m k))]
      (when (< (count operands) 2)
        (throw (u/format-error k (get m k)
                               (str (pr-str k) " needs at least two statement maps, got " (pr-str (get m k))))))
      (str/join (str " " word " ")
                (mapv (fn [q]
                        (cond
                          (map? q) (str "(" (statement ctx q) ")")
                          (u/raw? q) (str "(" (u/raw-sql q) ")")
                          :else (throw (u/format-error k q
                                                       (str "every operand of " (pr-str k)
                                                            " is a statement map, got " (pr-str q))))))
                      operands)))))

(defn- cte
  [ctx k item]
  (when-not (and (vector? item) (= 2 (count item)))
    (throw (u/format-error k item
                           (str "a common table expression is [:name {statement}] or"
                                " [[:name :col ...] {statement}], and " (pr-str k)
                                " takes a vector of them: [[:t {:select ...}]]; got " (pr-str item)))))
  (let [[head body] item
        [nm & cols] (if (vector? head) head [head])
        name-text (u/entity-name k nm false)
        cols-text (when (seq cols)
                    (str "(" (str/join ", " (mapv #(u/entity-name k % false) cols)) ")"))
        body-text (cond
                    (map? body) (statement ctx body)
                    (u/raw? body) (u/raw-sql body)
                    :else (throw (u/format-error k item
                                                 (str "the body of a common table expression is a"
                                                      " statement map, got " (pr-str body)))))]
    (str name-text cols-text " AS (" body-text ")")))

(defn- emit-with
  [ctx m k]
  (str "WITH "
       (when (= :with-recursive k) "RECURSIVE ")
       (str/join ", " (mapv #(cte ctx k %) (list-of k (get m k))))))

(def ^:private lock-strengths
  {:update "UPDATE" :no-key-update "NO KEY UPDATE" :share "SHARE" :key-share "KEY SHARE"})
(def ^:private lock-options {:nowait "NOWAIT" :skip-locked "SKIP LOCKED"})

(defn- emit-for
  [ctx m k]
  (let [v (get m k)
        [strength & more] (cond
                            (u/entity? v) [v]
                            (sequential? v) v
                            :else (throw (u/format-error k v
                                                         (str ":for is a lock strength or a vector"
                                                              " [strength table ... option], got "
                                                              (pr-str v)))))
        word (or (get lock-strengths (kw strength))
                 (throw (u/format-error k strength
                                        (str "unknown lock strength " (pr-str strength))
                                        {:allowed (vec (keys lock-strengths))})))
        {:keys [tables option]}
        (reduce (fn [acc x]
                  (if (contains? lock-options (kw x))
                    (if (:option acc)
                      (throw (u/format-error k v "only one of :nowait / :skip-locked"
                                             {:allowed (vec (keys lock-options))}))
                      (assoc acc :option (get lock-options (kw x))))
                    (update acc :tables conj (u/entity-name k x false))))
                {:tables []}
                more)]
    (str "FOR " word
         (when (seq tables) (str " OF " (str/join ", " tables)))
         (when option (str " " option)))))

;; ---------------------------------------------------------------------------
;; Statements
;; ---------------------------------------------------------------------------

(def ^:private clause-emitters
  "Every clause of a query in emission order (the PostgreSQL synopsis order)."
  [[:with emit-with]
   [:with-recursive emit-with]
   [:select emit-select]
   [:from emit-from]
   [:join (emit-join "INNER JOIN")]
   [:inner-join (emit-join "INNER JOIN")]
   [:left-join (emit-join "LEFT JOIN")]
   [:right-join (emit-join "RIGHT JOIN")]
   [:full-join (emit-join "FULL JOIN")]
   [:cross-join emit-cross-join]
   [:where (condition "WHERE")]
   [:group-by emit-group-by]
   [:having (condition "HAVING")]
   [:union (emit-union "UNION")]
   [:union-all (emit-union "UNION ALL")]
   [:order-by emit-order-by]
   [:limit (emit-count "LIMIT" true)]
   [:offset (emit-count "OFFSET" false)]
   [:for emit-for]])

(def ^:private clause-keys
  (into #{:distinct :distinct-on} (map first) clause-emitters))

(def ^:private heads #{:select :union :union-all})

(def ^:private union-clauses
  #{:with :with-recursive :union :union-all :order-by :limit :offset})

(defn- statement
  [ctx m]
  (when-not (map? m)
    (throw (u/format-error :statement m
                           (str "a statement is a map of clauses such as {:select [:a] :from [:t]}, got "
                                (pr-str m)))))
  (doseq [k (keys m)]
    (when-not (contains? clause-keys k)
      (throw (u/format-error :statement k
                             (str "unknown clause " (pr-str k) "; the clauses are listed under :allowed")
                             {:allowed (vec (sort clause-keys))}))))
  (let [present-heads (filterv #(contains? m %) [:select :union :union-all])]
    (when-not (= 1 (count present-heads))
      (throw (u/format-error :statement m
                             (str "a statement has exactly one of :select, :union, :union-all; got "
                                  (pr-str present-heads)))))
    (when (and (contains? m :with) (contains? m :with-recursive))
      (throw (u/format-error :statement (select-keys m [:with :with-recursive])
                             ":with and :with-recursive cannot both be given")))
    (when (not= :select (first present-heads))
      (doseq [k (keys m)]
        (when-not (contains? union-clauses k)
          (throw (u/format-error (first present-heads) k
                                 (str (pr-str k) " does not apply to a set operation; next to "
                                      (pr-str (first present-heads)) " only " (pr-str (vec (sort union-clauses)))
                                      " are accepted, the rest belong inside each operand")
                                 {:allowed (vec (sort union-clauses))}))))))
  (str/join " "
            (reduce (fn [acc [k emit]]
                      (if (contains? m k)
                        (conj acc (emit ctx m k))
                        acc))
                    []
                    clause-emitters)))

;; ---------------------------------------------------------------------------
;; Entry point
;; ---------------------------------------------------------------------------

(defn format
  "Renders the statement map `query` to {:text \"SELECT ...\" :values [...]},
   the exact map pg-cljs.core/query! takes. :text has $1, $2, ... in order
   of appearance (across nested statements and CTEs) and :values the
   corresponding values exactly as written, never transformed: nil,
   strings, numbers, booleans, BigInts and anything else pass through for
   the client to encode, and a value used twice is sent twice.

   Options, a map or nil:
   - :inline true renders every value as a literal in :text and returns
     :values []: strings as '...' with quotes doubled, nil as NULL, booleans
     as TRUE / FALSE, numbers and BigInts as their decimal text; NaN,
     ±Infinity and any other value (an object, a date) are a format-error.
     Inline text is for SQL consoles and debugging only: it is built from
     caller data with no server-side protection, so inlining untrusted input
     is SQL injection.

   Anything malformed (a value where a name is required, a mixed-case or
   exotic entity, an unknown clause or operator, an empty :select, ...) is a
   :sql-cljs/format-error with :op and :arg; see sql-cljs.util/format-error."
  ([query] (format query nil))
  ([query opts]
   (when-not (or (nil? opts) (map? opts))
     (throw (u/format-error :format opts (str "options must be a map or nil, got " (pr-str opts)))))
   (doseq [k (keys opts)]
     (when-not (= :inline k)
       (throw (u/format-error :format k (str "unknown option " (pr-str k)) {:allowed [:inline]}))))
   (when-not (or (nil? (:inline opts)) (boolean? (:inline opts)))
     (throw (u/format-error :format (:inline opts)
                            (str ":inline must be true or false, got " (pr-str (:inline opts))))))
   (when-not (map? query)
     (throw (u/format-error :format query
                            (str "format takes a statement map such as {:select [:a] :from [:t]}, got "
                                 (pr-str query)))))
   (let [ctx (context opts)
         text (statement ctx query)]
     {:text text :values @(:params ctx)})))
