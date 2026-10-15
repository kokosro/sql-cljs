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

   Data modification, one head per statement (:with applies to all three
   of INSERT, UPDATE and DELETE):

   - :insert-into :t or [:t :alias] (always INSERT INTO t AS alias), with
     :columns [:a :b] and :values, a vector of rows: vectors, [[1 \"x\"] [2
     \"y\"]], or maps, [{:a 1 :b \"x\"}], whose keys become the column list
     (the first map's order, or the :columns order) and must be the same in
     every row. :default in a value slot is the DEFAULT key word in both
     modes. A statement map as :values is INSERT ... SELECT.
   - :on-conflict, a map: the target, :on-columns [:id] (a column or an
     index expression vector, [:lower :email]) with an optional :index-where
     predicate, or :on-constraint :name, or neither (DO NOTHING only); the
     action, :do-nothing true or :do-update-set (a map or a vector of pairs
     like :set, :excluded/col naming the proposed row) with an optional
     :where condition.
   - :returning, the same items as :select: :id, [:*], [[:lower :name] :n].
   - :update :t or [:t :alias] with :set, a map {:a 1 :b :c} or a vector of
     [column expression] pairs (the vector keeps the order past eight
     assignments); columns are never table-qualified; :default assigns the
     default. Then :from (as in SELECT), :where, :returning.
   - :delete-from :t or [:t :alias], then :using (a FROM list), :where,
     :returning.
   - :truncate :t or [:t :u], always TRUNCATE TABLE, with the flags
     :restart-identity / :continue-identity and :cascade / :restrict, each
     true or false.

   Data definition, one head per statement. DDL takes no parameters, so
   every literal in it (a DEFAULT, a CHECK constant, a partial-index
   predicate, a view body) is spelled out inline whatever the :inline
   option, and :values is [] (a date or an object with no literal spelling
   is a format-error). Names are plain lower-case entities as everywhere;
   a mixed-case or exotic name goes through raw.

   - :create-table :t with :if-not-exists true and :columns, a vector of
     column definitions [name type & constraints]: [:id :serial
     :primary-key], [:name [:varchar 40] :not-null], [:price :numeric
     [:default 0]], [:did :integer [:references :d :did] [:on-delete
     :cascade]]. A type is a keyword rendered upper-case (:text, :bigint;
     :double-precision and :timestamp-with-time-zone spell multi-word
     types, the - becoming a space), a vector [keyword & numbers] for
     NAME(n, ...) ([:varchar 40], [:numeric 10 2]), or raw for anything
     else (array types, (raw \"text[]\")); a string is a value, never a
     type. Column constraints, in the order written: :not-null, :null,
     :unique, :primary-key, [:default expr] (an operator expression is
     parenthesized), [:check expr] (always parenthesized), [:references
     :t & cols], then [:on-delete action] / [:on-update action] (actions
     :no-action, :restrict, :cascade, :set-null, :set-default),
     [:constraint :name constraint], or raw. :constraints, a vector of
     table constraints: [:primary-key :a :b], [:unique :a], [:check expr],
     [:foreign-key [:a :b] [:references :t :c :d] [:on-delete :cascade]],
     [:constraint :name constraint], or raw; they follow the columns.
   - :drop-table :t or [:t :u], with :if-exists, :cascade / :restrict.
   - :alter-table :t with :if-exists and :actions, a vector of
     [:add-column [name type & constraints] :if-not-exists], [:drop-column
     :c :if-exists :cascade|:restrict] and [:rename-column :old :new]
     (the options after the arguments are optional); several ADD / DROP
     actions are comma-separated, RENAME COLUMN stands alone.
   - :create-index :name (nil for an unnamed index) with :unique,
     :concurrently, :if-not-exists, :on :t, :using :gin (the method),
     :columns, a vector of :order-by items (:a, [:b :desc :nulls-last],
     [[:lower :email]]; an operator expression is parenthesized), and
     :where, the partial-index predicate.
   - :drop-index :i or [:i :j], with :concurrently (then exactly one name
     and no :cascade), :if-exists, :cascade / :restrict.
   - :create-view :v with :or-replace, :columns [:a :b] (the view's column
     names) and :as, the body: a statement map, rendered without
     parentheses, or raw.
   - :drop-view :v or [:v :w], with :if-exists, :cascade / :restrict.

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
   entity or raw fragment; empty or anything else is a format-error."
  [op x]
  (cond
    (or (u/entity? x) (u/raw? x)) [x]
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
  "An item of an output list (SELECT, RETURNING): an entity, the star, a
   subquery, a raw fragment, [expression] or [expression alias]."
  [ctx op x]
  (cond
    (vector? x)
    (case (count x)
      1 (expression ctx op (first x) true)
      2 (str (expression ctx op (first x) true) " AS " (alias-name op (second x) (first x)))
      (throw (u/format-error op x
                             (str "a vector in " (pr-str op) " is [expression] or [expression alias], got "
                                  (pr-str x) "; a function call is nested: [[:count :*]] or"
                                  " [[:count :*] :total]"))))

    (or (u/entity? x) (u/raw? x) (map? x))
    (expression ctx op x true)

    (collection-in-expression? x)
    (expression ctx op x true)

    :else
    (throw (u/format-error op x
                           (str (pr-str x) " is a value, not a column"
                                (when (string? x) " (strings are values, never names)")
                                "; to return a value as a parameter write it nested, [["
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
        items (mapv #(select-item ctx :select %) (list-of :select (:select m)))]
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
  [op x acc md0]
  (let [md (kw md0)]
    (cond
      (contains? directions md)
      (if (:dir acc)
        (throw (u/format-error op x "only one of :asc / :desc per item"))
        (assoc acc :dir (get directions md)))

      (contains? nulls-orders md)
      (if (:nulls acc)
        (throw (u/format-error op x "only one of :nulls-first / :nulls-last per item"))
        (assoc acc :nulls (get nulls-orders md)))

      :else
      (throw (u/format-error op x
                             (str "an item of " (pr-str op) " is [expression & modifiers] with"
                                  " modifiers among :asc :desc :nulls-first :nulls-last;"
                                  " an expression with its own arguments is nested,"
                                  " [[:lower :name] :asc]; got " (pr-str x))
                             {:allowed order-modifiers})))))

(defn- order-item
  "An item of ORDER BY (or of an index column list, with :op op): an
   expression, or [expression & modifiers]. paren? parenthesizes an operator
   expression, as an index column must be."
  ([ctx x] (order-item ctx :order-by x false))
  ([ctx op x paren?]
   (let [render (fn [e]
                  (let [text (expression ctx op e)]
                    (if (and paren? (operator-form? e)) (str "(" text ")") text)))]
     (cond
       (and (u/entity? x) (or (contains? directions (kw x)) (contains? nulls-orders (kw x))))
       (throw (u/format-error op x
                              (str (pr-str x) " is a sort modifier, not a column: an item of "
                                   (pr-str op) " with modifiers is a vector, [[:a :desc]]")))

       (vector? x)
       (do
         (when (empty? x)
           (throw (u/format-error op x (str "an empty vector is not an item of " (pr-str op)))))
         (let [text (render (first x))
               {:keys [dir nulls]} (reduce #(order-modifier op x %1 %2) {} (rest x))]
           (str text (when dir (str " " dir)) (when nulls (str " " nulls)))))

       :else (render x)))))

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
;; Data modification: INSERT, UPDATE, DELETE, TRUNCATE
;; ---------------------------------------------------------------------------

(defn- target-base
  [op t]
  (cond
    (u/entity? t) (u/entity-name op t false)
    (u/raw? t) (u/raw-sql t)
    :else (throw (u/format-error op t
                                 (str "the target table of " (pr-str op) " is a keyword or symbol"
                                      " (or a raw fragment), got " (pr-str t)
                                      (when (string? t) " (strings are values, never names)")
                                      (when (map? t) " (a subquery is never a target)"))))))

(defn- target-ref
  "The target table of INSERT INTO, UPDATE and DELETE FROM: t, [t] or
   [t :alias], always rendered with AS."
  [op x]
  (if (vector? x)
    (case (count x)
      1 (target-base op (first x))
      2 (str (target-base op (first x)) " AS " (alias-name op (second x) (first x)))
      (throw (u/format-error op x
                             (str (pr-str op) " is a table or [table alias], got " (pr-str x)))))
    (target-base op x)))

(defn- default?
  [x]
  (= :default (kw x)))

(defn- value-or-default
  "An element of a VALUES row or the right-hand side of an assignment:
   :default is the DEFAULT key word (in both modes), anything else an
   expression."
  [ctx op x]
  (if (default? x) "DEFAULT" (expression ctx op x)))

(defn- assignment-column
  "The column of a SET assignment: a plain, never table-qualified, entity."
  [op c]
  (when (and (u/entity? c) (< 1 (count (u/entity-parts c))))
    (throw (u/format-error op c
                           (str "the column of an assignment is never table-qualified (SET col = ..., not"
                                " SET t.col = ...), got " (pr-str c)))))
  (u/entity-name op c false))

(defn- assignments
  "The assignments of SET and DO UPDATE SET: a map {column expression} or a
   vector of [column expression] pairs, rendered col = expr, comma-separated
   in the order given (a map with more than eight entries iterates in hash
   order, so the vector form is the one that keeps the caller's order)."
  [ctx op x]
  (let [pairs (cond
                (map? x) (seq x)
                (vector? x) (do (doseq [p x]
                                  (when-not (and (vector? p) (= 2 (count p)))
                                    (throw (u/format-error op p
                                                           (str "an assignment in a vector " (pr-str op)
                                                                " is a [column expression] pair, got "
                                                                (pr-str p))))))
                                x)
                :else (throw (u/format-error op x
                                             (str (pr-str op) " is a map {column expression} or a vector"
                                                  " of [column expression] pairs, got " (pr-str x)))))]
    (when (empty? pairs)
      (throw (u/format-error op x (str (pr-str op) " needs at least one assignment"))))
    (str/join ", " (mapv (fn [[c v]]
                           (str (assignment-column op c) " = " (value-or-default ctx op v)))
                         pairs))))

;; INSERT

(defn- insert-columns
  "The column list of an INSERT as [op columns]: :columns when given, else
   the keys of the first row when :values is a vector of maps, else nil."
  [m]
  (let [v (:values m)]
    (cond
      (contains? m :columns) [:columns (list-of :columns (:columns m))]
      (and (sequential? v) (map? (first v))) [:values (vec (keys (first v)))]
      :else nil)))

(defn- emit-insert-into
  [_ctx m k]
  (let [[op cols] (insert-columns m)]
    (str "INSERT INTO " (target-ref k (get m k))
         (when (seq cols)
           (str " (" (str/join ", " (mapv #(u/entity-name op % false) cols)) ")")))))

(defn- row-of-map
  "The values of a map row in column order; the row must have exactly the
   column keys."
  [k cols row]
  (when (empty? row)
    (throw (u/format-error k row "a map row of :values must have at least one column")))
  (when-not (= (set (keys row)) (set cols))
    (throw (u/format-error k row
                           (str "every map in :values must have the same keys, the columns "
                                (pr-str cols) "; got " (pr-str row)
                                " (a column a row leaves out is written :default)")
                           {:columns cols})))
  (mapv #(get row %) cols))

(defn- values-row
  [k maps? cols row]
  (cond
    (not= maps? (map? row))
    (throw (u/format-error k row
                           (str ":values mixes vector rows and map rows; use one shape for every row,"
                                " got " (pr-str row))))
    maps? (row-of-map k cols row)
    (sequential? row) (vec row)
    :else
    (throw (u/format-error k row
                           (str "a row of :values is a vector of values, [1 \"x\"], or a map"
                                " {:a 1 :b \"x\"}, and :values is a vector of rows: [[1 \"x\"]]; got "
                                (pr-str row))))))

(defn- emit-values
  [ctx m k]
  (let [v (get m k)]
    (cond
      (map? v) (statement ctx v)
      (u/raw? v) (u/raw-sql v)
      (sequential? v)
      (let [rows (vec v)
            _ (when (empty? rows)
                (throw (u/format-error k v ":values must not be empty: one row at least, [[1 2]] or [{:a 1}]")))
            maps? (map? (first rows))
            [_ cols] (insert-columns m)
            rows (mapv #(values-row k maps? cols %) rows)
            width (count (first rows))]
        (doseq [row rows]
          (when (empty? row)
            (throw (u/format-error k row "a row of :values must not be empty")))
          (when-not (= width (count row))
            (throw (u/format-error k row
                                   (str "every row of :values has the same number of values, the first has "
                                        width ", got " (pr-str row))))))
        (when (and (seq cols) (not= width (count cols)))
          (throw (u/format-error k (first rows)
                                 (str ":columns lists " (count cols) " columns but a row of :values has "
                                      width " values: " (pr-str (first rows)))
                                 {:columns cols})))
        (str "VALUES "
             (str/join ", " (mapv (fn [row]
                                    (str "(" (str/join ", " (mapv #(value-or-default ctx k %) row)) ")"))
                                  rows))))
      :else
      (throw (u/format-error k v
                             (str ":values is a vector of rows ([[1 2] [3 4]] or [{:a 1} {:a 2}]) or a"
                                  " statement map (INSERT ... SELECT), got " (pr-str v)))))))

(def ^:private on-conflict-keys
  [:on-columns :on-constraint :index-where :do-nothing :do-update-set :where])

(defn- conflict-target-item
  [ctx k x]
  (cond
    (u/entity? x) (u/entity-name k x false)
    (u/raw? x) (u/raw-sql x)
    (vector? x) (str "(" (expression ctx k x) ")")
    :else (throw (u/format-error k x
                                 (str "an item of :on-columns is a column or an index expression vector"
                                      " ([:lower :email]), got " (pr-str x))))))

(defn- flag
  "The boolean of a flag key: true, false or absent (nil); anything else is
   a format-error with :op k."
  [m k]
  (let [v (get m k)]
    (cond
      (true? v) true
      (or (nil? v) (false? v)) false
      :else (throw (u/format-error k v (str (pr-str k) " must be true or false, got " (pr-str v)))))))

(defn- emit-on-conflict
  [ctx m k]
  (let [c (get m k)]
    (when-not (map? c)
      (throw (u/format-error k c
                             (str ":on-conflict is a map with :on-columns or :on-constraint and"
                                  " :do-nothing true or :do-update-set, e.g."
                                  " {:on-columns [:id] :do-update-set {:a :excluded/a}}; got " (pr-str c)))))
    (doseq [x (keys c)]
      (when-not (contains? (set on-conflict-keys) x)
        (throw (u/format-error k x
                               (str "unknown key " (pr-str x) " in :on-conflict; the keys are listed under :allowed")
                               {:allowed on-conflict-keys}))))
    (let [nothing? (flag c :do-nothing)
          update? (contains? c :do-update-set)]
      (when (and (contains? c :on-columns) (contains? c :on-constraint))
        (throw (u/format-error k (select-keys c [:on-columns :on-constraint])
                               ":on-columns and :on-constraint cannot both be given")))
      (when (and (contains? c :index-where) (not (contains? c :on-columns)))
        (throw (u/format-error k (:index-where c)
                               ":index-where is the predicate of the :on-columns index inference and needs :on-columns")))
      (when-not (or nothing? update?)
        (throw (u/format-error k c
                               ":on-conflict needs its action, :do-nothing true or :do-update-set {...}")))
      (when (and nothing? update?)
        (throw (u/format-error k (select-keys c [:do-nothing :do-update-set])
                               ":do-nothing and :do-update-set cannot both be given")))
      (when (and (contains? c :where) (not update?))
        (throw (u/format-error k (:where c)
                               ":where in :on-conflict is the condition of DO UPDATE and needs :do-update-set")))
      (str "ON CONFLICT"
           (when (contains? c :on-columns)
             (str " ("
                  (str/join ", " (mapv #(conflict-target-item ctx k %) (list-of k (:on-columns c))))
                  ")"
                  (when (contains? c :index-where)
                    (str " " ((condition "WHERE") ctx c :index-where)))))
           (when (contains? c :on-constraint)
             (str " ON CONSTRAINT " (u/entity-name k (:on-constraint c) false)))
           (if nothing?
             " DO NOTHING"
             (str " DO UPDATE SET " (assignments ctx :do-update-set (:do-update-set c))
                  (when (contains? c :where)
                    (str " " ((condition "WHERE") ctx c :where)))))))))

(defn- emit-returning
  [ctx m k]
  (str "RETURNING " (str/join ", " (mapv #(select-item ctx k %) (list-of k (get m k))))))

;; UPDATE and DELETE

(defn- emit-update
  [_ctx m k]
  (str "UPDATE " (target-ref k (get m k))))

(defn- emit-set
  [ctx m k]
  (str "SET " (assignments ctx k (get m k))))

(defn- emit-delete-from
  [_ctx m k]
  (str "DELETE FROM " (target-ref k (get m k))))

(defn- emit-using
  [ctx m k]
  (str "USING " (str/join ", " (mapv #(table-ref ctx k %) (list-of k (get m k))))))

;; TRUNCATE

(defn- emit-truncate
  [_ctx m k]
  (when (and (flag m :restart-identity) (flag m :continue-identity))
    (throw (u/format-error k (select-keys m [:restart-identity :continue-identity])
                           ":restart-identity and :continue-identity cannot both be true")))
  (when (and (flag m :cascade) (flag m :restrict))
    (throw (u/format-error k (select-keys m [:cascade :restrict])
                           ":cascade and :restrict cannot both be true")))
  (str "TRUNCATE TABLE "
       (str/join ", " (mapv #(target-base k %) (list-of k (get m k))))
       (when (flag m :restart-identity) " RESTART IDENTITY")
       (when (flag m :continue-identity) " CONTINUE IDENTITY")
       (when (flag m :cascade) " CASCADE")
       (when (flag m :restrict) " RESTRICT")))

;; ---------------------------------------------------------------------------
;; Data definition: CREATE / DROP / ALTER TABLE, CREATE / DROP INDEX,
;; CREATE / DROP VIEW. DDL carries no parameters: `statement` switches the
;; context to inline rendering for these heads, so every literal (a DEFAULT
;; value, a CHECK constant, a partial-index predicate, a view body) is spelled
;; out whatever the :inline option, and :values stays [].
;; ---------------------------------------------------------------------------

(defn- plain-name
  "A name that is never qualified (a column, a constraint, an index): an
   entity of one part, or a raw fragment."
  [op x]
  (cond
    (u/raw? x) (u/raw-sql x)

    (and (u/entity? x) (< 1 (count (u/entity-parts x))))
    (throw (u/format-error op x
                           (str (pr-str x) " is qualified; a column, constraint or index name is a"
                                " plain name here (no schema, no table prefix)")))

    :else (u/entity-name op x false)))

(defn- type-word
  "The upper-case text of the type keyword x (t is the whole type, for
   errors): lower-case words joined by - become space-separated words,
   :double-precision -> DOUBLE PRECISION; a schema-qualified :s/t is S.T."
  [op t x]
  (let [parts (u/entity-parts x)]
    (doseq [part parts
            word (str/split part #"-" -1)]
      (when-not (re-matches u/identifier-pattern word)
        (throw (u/format-error op t
                               (str (pr-str x) " is not a type name: a type is a keyword of lower-case"
                                    " words joined by - (:text, :bigint, :double-precision,"
                                    " :timestamp-with-time-zone), rendered upper-case, or a raw"
                                    " fragment spliced verbatim, (raw \"text[]\")")
                               {:entity x}))))
    (str/upper-case (str/join "." (mapv #(str/replace % "-" " ") parts)))))

(defn- type-name
  "The SQL text of a column type: a keyword -> its upper-case word(s), a
   vector [keyword & numbers] -> NAME(n, ...), a raw fragment -> verbatim.
   A string is a value, never a type."
  [op t]
  (cond
    (u/raw? t) (u/raw-sql t)
    (u/entity? t) (type-word op t t)

    (vector? t)
    (let [[nm & args] t]
      (when-not (u/entity? nm)
        (throw (u/format-error op t
                               (str "a type with arguments is [keyword & numbers], [:varchar 255] or"
                                    " [:numeric 10 2]; got " (pr-str t)))))
      (when (empty? args)
        (throw (u/format-error op t
                               (str "a type with arguments is [keyword & numbers], [:varchar 255];"
                                    " a type without arguments is the bare keyword " (pr-str nm)))))
      (doseq [a args]
        (when-not (number? a)
          (throw (u/format-error op t
                                 (str "the arguments of a type are numbers, [:varchar 255]; got "
                                      (pr-str a) " in " (pr-str t)
                                      (when (string? a) " (strings are values, never type arguments)")
                                      "; a type spelled otherwise goes through the raw escape hatch")))))
      (str (type-word op t nm) "(" (str/join ", " (mapv str args)) ")"))

    :else
    (throw (u/format-error op t
                           (str "a column type is a keyword (:text), a vector with arguments"
                                " ([:varchar 255]) or a raw fragment, got " (pr-str t)
                                (when (string? t)
                                  (str " (strings are values, never types; (raw " (pr-str t)
                                       ") splices a type spelled by hand)")))))))

(def ^:private referential-actions
  {:no-action "NO ACTION" :restrict "RESTRICT" :cascade "CASCADE"
   :set-null "SET NULL" :set-default "SET DEFAULT"})

(def ^:private action-words {:on-delete "ON DELETE" :on-update "ON UPDATE"})

(defn- references-form?
  [x]
  (and (vector? x) (= :references (kw (first x)))))

(defn- action-form?
  [x]
  (and (vector? x) (contains? action-words (kw (first x)))))

(defn- referential-action
  "[:on-delete action] or [:on-update action] -> ON DELETE CASCADE, ..."
  [op x]
  (when-not (= 2 (count x))
    (throw (u/format-error op x
                           (str (pr-str (first x)) " takes exactly one referential action, "
                                (pr-str (vec (keys referential-actions))) "; got " (pr-str x)))))
  (let [a (second x)
        word (get referential-actions (kw a))]
    (when-not word
      (throw (u/format-error op x
                             (str "unknown referential action " (pr-str a)
                                  "; the actions are listed under :allowed")
                             {:allowed (vec (keys referential-actions))})))
    (str (get action-words (kw (first x))) " " word)))

(defn- references
  "[:references table & columns] -> REFERENCES t (a, b)."
  [op x]
  (when (< (count x) 2)
    (throw (u/format-error op x "[:references table & columns] names the referenced table")))
  (let [[_ t & cols] x]
    (str "REFERENCES " (target-base op t)
         (when (seq cols)
           (str " (" (str/join ", " (mapv #(plain-name op %) cols)) ")")))))

(defn- default-constraint
  "[:default expression] -> DEFAULT literal; an operator expression is
   parenthesized, DEFAULT (1 + 2), as an operand is."
  [ctx op x]
  (when-not (= 2 (count x))
    (throw (u/format-error op x (str "[:default expression] takes exactly one expression, got " (pr-str x)))))
  (str "DEFAULT " (operand ctx op (second x))))

(defn- check-constraint
  "[:check expression] -> CHECK (expression), always parenthesized."
  [ctx op x]
  (when-not (= 2 (count x))
    (throw (u/format-error op x (str "[:check expression] takes exactly one expression, got " (pr-str x)))))
  (let [c (second x)]
    (cond
      (u/raw? c) (str "CHECK (" (u/raw-sql c) ")")
      (vector? c) (str "CHECK (" (expression ctx op c) ")")
      :else (throw (u/format-error op x
                                   (str "[:check expression] takes an expression vector such as"
                                        " [:> :a 0], got " (pr-str c)))))))

(defn- named-constraint
  "[:constraint name constraint] -> CONSTRAINT name ..., the body rendered
   by render (a column or a table constraint)."
  [ctx op x render]
  (when-not (= 3 (count x))
    (throw (u/format-error op x
                           (str "[:constraint name constraint] names one constraint, e.g."
                                " [:constraint :firstkey :primary-key] or"
                                " [:constraint :con1 [:check [:> :did 100]]]; got " (pr-str x)))))
  (let [[_ nm body] x]
    (when (and (vector? body) (= :constraint (kw (first body))))
      (throw (u/format-error op x "a named constraint cannot be named twice")))
    (str "CONSTRAINT " (plain-name op nm) " " (render ctx op body))))

(def ^:private column-constraint-words
  {:not-null "NOT NULL" :null "NULL" :unique "UNIQUE" :primary-key "PRIMARY KEY"})

(def ^:private column-constraint-heads
  [:check :constraint :default :on-delete :on-update :references])

(defn- column-constraint
  "One constraint of a column definition: a keyword (:not-null, :null,
   :unique, :primary-key), a vector ([:default x], [:check expr],
   [:references t & cols], [:on-delete action], [:on-update action],
   [:constraint name constraint]) or a raw fragment."
  [ctx op x]
  (cond
    (u/raw? x) (u/raw-sql x)

    (u/entity? x)
    (or (get column-constraint-words (kw x))
        (throw (u/format-error op x
                               (str "unknown column constraint " (pr-str x)
                                    "; the keyword constraints are listed under :allowed, the"
                                    " others are vectors: [:default x], [:check expr],"
                                    " [:references t :col], [:on-delete :cascade],"
                                    " [:constraint :name ...]")
                               {:allowed (vec (keys column-constraint-words))})))

    (and (vector? x) (seq x))
    (case (kw (first x))
      :default (default-constraint ctx op x)
      :check (check-constraint ctx op x)
      :references (references op x)
      (:on-delete :on-update) (referential-action op x)
      :constraint (named-constraint ctx op x column-constraint)
      (throw (u/format-error op x
                             (str "unknown column constraint " (pr-str (first x))
                                  "; a constraint vector starts with one of the keys listed under :allowed")
                             {:allowed column-constraint-heads})))

    :else
    (throw (u/format-error op x
                           (str "a column constraint is a keyword (:not-null, :unique, :primary-key),"
                                " a vector ([:default 0], [:check [:> :a 0]], [:references :t :id])"
                                " or a raw fragment, got " (pr-str x))))))

(defn- column-definition
  "[name type & constraints] -> name TYPE constraint ..., the constraints in
   the order written; a referential action follows its [:references ...]."
  [ctx op x]
  (when-not (and (vector? x) (<= 2 (count x)))
    (throw (u/format-error op x
                           (str "a column definition is [name type & constraints], e.g."
                                " [:id :serial :primary-key] or [:name [:varchar 40] :not-null]; got "
                                (pr-str x)))))
  (let [[nm t & cs] x
        head [(plain-name op nm) (type-name op t)]
        [texts _] (reduce (fn [[acc prev] c]
                            (when (and (action-form? c)
                                       (not (or (references-form? prev) (action-form? prev)
                                                (and (vector? prev) (= :constraint (kw (first prev)))
                                                     (references-form? (nth prev 2 nil))))))
                              (throw (u/format-error op c
                                                     (str (pr-str (first c)) " follows the [:references ...]"
                                                          " constraint it belongs to, as in"
                                                          " [:did :integer [:references :d :id]"
                                                          " [:on-delete :cascade]]"))))
                            [(conj acc (column-constraint ctx op c)) c])
                          [head nil]
                          cs)]
    (str/join " " texts)))

(def ^:private table-constraint-heads
  [:check :constraint :foreign-key :primary-key :unique])

(defn- key-columns
  [op x cols]
  (when (empty? cols)
    (throw (u/format-error op x (str (pr-str (first x)) " needs at least one column, got " (pr-str x)))))
  (str "(" (str/join ", " (mapv #(plain-name op %) cols)) ")"))

(defn- foreign-key
  "[:foreign-key columns [:references t & columns] & actions] -> FOREIGN KEY
   (a, b) REFERENCES t (c, d) ON DELETE ... ON UPDATE ...; columns is one
   name or a vector of names."
  [op x]
  (let [[_ cols refs & actions] x
        cols (cond
               (or (u/entity? cols) (u/raw? cols)) [cols]
               (sequential? cols) (vec cols)
               :else (throw (u/format-error op x
                                            (str "[:foreign-key columns [:references ...]]: columns is a"
                                                 " name or a vector of names, got " (pr-str cols)))))]
    (when-not (references-form? refs)
      (throw (u/format-error op x
                             (str "a foreign key is [:foreign-key columns [:references table & columns]"
                                  " & actions], got " (pr-str x)))))
    (doseq [a actions]
      (when-not (action-form? a)
        (throw (u/format-error op a
                               (str "after the references of a foreign key come only [:on-delete action]"
                                    " and [:on-update action], got " (pr-str a))))))
    (str "FOREIGN KEY " (key-columns op x cols) " " (references op refs)
         (apply str (mapv #(str " " (referential-action op %)) actions)))))

(defn- table-constraint
  "One table constraint of CREATE TABLE: [:primary-key & cols], [:unique &
   cols], [:check expr], [:foreign-key ...], [:constraint name constraint],
   or a raw fragment."
  [ctx op x]
  (cond
    (u/raw? x) (u/raw-sql x)

    (and (vector? x) (seq x))
    (case (kw (first x))
      :primary-key (str "PRIMARY KEY " (key-columns op x (rest x)))
      :unique (str "UNIQUE " (key-columns op x (rest x)))
      :check (check-constraint ctx op x)
      :foreign-key (foreign-key op x)
      :constraint (named-constraint ctx op x table-constraint)
      (throw (u/format-error op x
                             (str "unknown table constraint " (pr-str (first x))
                                  "; a table constraint starts with one of the keys listed under :allowed")
                             {:allowed table-constraint-heads})))

    :else
    (throw (u/format-error op x
                           (str "a table constraint is a vector such as [:primary-key :a :b],"
                                " [:unique :c], [:check [:> :a 0]], [:foreign-key :d [:references :e :id]]"
                                " or [:constraint :name ...], or a raw fragment; got " (pr-str x))))))

(defn- emit-create-table
  [ctx m k]
  (let [cols (mapv #(column-definition ctx :columns %) (list-of :columns (:columns m)))
        constraints (when (contains? m :constraints)
                      (mapv #(table-constraint ctx :constraints %)
                            (list-of :constraints (:constraints m))))]
    (str "CREATE TABLE " (when (flag m :if-not-exists) "IF NOT EXISTS ")
         (target-base k (get m k))
         " (" (str/join ", " (into cols constraints)) ")")))

;; DROP TABLE, DROP INDEX, DROP VIEW

(defn- drop-behavior
  [m k]
  (when (and (flag m :cascade) (flag m :restrict))
    (throw (u/format-error k (select-keys m [:cascade :restrict])
                           ":cascade and :restrict cannot both be true")))
  (cond
    (flag m :cascade) " CASCADE"
    (flag m :restrict) " RESTRICT"
    :else nil))

(defn- emit-drop
  [word]
  (fn [_ctx m k]
    (let [names (list-of k (get m k))
          concurrently? (flag m :concurrently)]
      (when concurrently?
        (when (flag m :cascade)
          (throw (u/format-error k (select-keys m [:concurrently :cascade])
                                 "DROP INDEX CONCURRENTLY does not support CASCADE")))
        (when (< 1 (count names))
          (throw (u/format-error k (get m k)
                                 (str "DROP INDEX CONCURRENTLY drops exactly one index, got "
                                      (pr-str (get m k)))))))
      (str "DROP " word
           (when concurrently? " CONCURRENTLY")
           (when (flag m :if-exists) " IF EXISTS")
           " " (str/join ", " (mapv #(target-base k %) names))
           (drop-behavior m k)))))

;; ALTER TABLE

(def ^:private alter-actions [:add-column :drop-column :rename-column])

(defn- action-flags
  "The set of flag keywords after the arguments of an action, each at most
   once and among allowed."
  [op x flags allowed]
  (reduce (fn [acc f]
            (let [f (kw f)]
              (when-not (contains? (set allowed) f)
                (throw (u/format-error op f
                                       (str "unknown option " (pr-str f) " in " (pr-str x)
                                            "; the options of " (pr-str op) " are listed under :allowed")
                                       {:allowed allowed})))
              (when (contains? acc f)
                (throw (u/format-error op x (str (pr-str f) " is given twice in " (pr-str x)))))
              (conj acc f)))
          #{}
          flags))

(defn- alter-action
  [ctx k x]
  (when-not (and (vector? x) (seq x))
    (throw (u/format-error k x
                           (str "an action of :actions is [:add-column [name type & constraints]"
                                " & options], [:drop-column name & options] or"
                                " [:rename-column old new]; got " (pr-str x)))))
  (let [a (kw (first x))]
    (case a
      :add-column
      (do (when (< (count x) 2)
            (throw (u/format-error a x "[:add-column [name type & constraints] & options] needs the column definition")))
          (let [flags (action-flags a x (nnext x) [:if-not-exists])]
            (str "ADD COLUMN " (when (:if-not-exists flags) "IF NOT EXISTS ")
                 (column-definition ctx a (second x)))))

      :drop-column
      (do (when (< (count x) 2)
            (throw (u/format-error a x "[:drop-column name & options] needs the column name")))
          (let [flags (action-flags a x (nnext x) [:if-exists :cascade :restrict])]
            (when (and (:cascade flags) (:restrict flags))
              (throw (u/format-error a x ":cascade and :restrict cannot both be given")))
            (str "DROP COLUMN " (when (:if-exists flags) "IF EXISTS ")
                 (plain-name a (second x))
                 (when (:cascade flags) " CASCADE")
                 (when (:restrict flags) " RESTRICT"))))

      :rename-column
      (do (when-not (= 3 (count x))
            (throw (u/format-error a x (str "[:rename-column old new] takes exactly two names, got " (pr-str x)))))
          (str "RENAME COLUMN " (plain-name a (second x)) " TO " (plain-name a (nth x 2))))

      (throw (u/format-error k x
                             (str "unknown action " (pr-str (first x))
                                  "; the actions are listed under :allowed")
                             {:allowed alter-actions})))))

(defn- emit-alter-table
  [ctx m k]
  (let [actions (list-of :actions (:actions m))
        rename? (some #(and (vector? %) (= :rename-column (kw (first %)))) actions)]
    (when (and rename? (< 1 (count actions)))
      (throw (u/format-error :actions (:actions m)
                             "RENAME COLUMN is a form of its own and cannot be combined with other actions")))
    (str "ALTER TABLE " (when (flag m :if-exists) "IF EXISTS ")
         (target-base k (get m k)) " "
         (str/join ", " (mapv #(alter-action ctx :actions %) actions)))))

;; CREATE INDEX

(defn- index-method
  [m]
  (let [v (:using m)
        v (if (and (sequential? v) (= 1 (count v))) (first v) v)]
    (cond
      (u/entity? v) (u/entity-name :using v false)
      (u/raw? v) (u/raw-sql v)
      :else (throw (u/format-error :using v
                                   (str ":using in :create-index is one index method, :btree, :hash,"
                                        " :gist, :spgist, :gin or :brin; got " (pr-str v)))))))

(defn- index-column
  "An item of the CREATE INDEX column list: an :order-by item whose
   expression is a column, an expression vector or a raw fragment, never a
   value."
  [ctx x]
  (let [e (if (and (vector? x) (seq x)) (first x) x)]
    (when-not (or (u/entity? e) (u/raw? e) (vector? e))
      (throw (u/format-error :columns e
                             (str "an index column is a column name, an expression vector"
                                  " ([[:lower :email]]) or a raw fragment, got " (pr-str e)
                                  (when (string? e) " (strings are values, never names)"))))))
  (order-item ctx :columns x true))

(defn- emit-create-index
  [ctx m k]
  (let [nm (get m k)]
    (when (and (nil? nm) (flag m :if-not-exists))
      (throw (u/format-error k m "CREATE INDEX IF NOT EXISTS needs an index name; an unnamed index (:create-index nil) cannot be conditional")))
    (str "CREATE " (when (flag m :unique) "UNIQUE ") "INDEX"
         (when (flag m :concurrently) " CONCURRENTLY")
         (when (flag m :if-not-exists) " IF NOT EXISTS")
         (when (some? nm) (str " " (plain-name k nm)))
         " ON " (target-base :on (:on m))
         (when (contains? m :using) (str " USING " (index-method m)))
         " (" (str/join ", " (mapv #(index-column ctx %) (list-of :columns (:columns m)))) ")")))

;; CREATE VIEW

(defn- emit-create-view
  [ctx m k]
  (let [body (:as m)]
    (str "CREATE " (when (flag m :or-replace) "OR REPLACE ") "VIEW "
         (target-base k (get m k))
         (when (contains? m :columns)
           (str " (" (str/join ", " (mapv #(plain-name :columns %) (list-of :columns (:columns m)))) ")"))
         " AS "
         (cond
           (map? body) (statement ctx body)
           (u/raw? body) (u/raw-sql body)
           :else (throw (u/format-error :as body
                                        (str "the body of a view (:as) is a statement map, {:select ...},"
                                             " or a raw fragment, got " (pr-str body))))))))

;; ---------------------------------------------------------------------------
;; Statements
;; ---------------------------------------------------------------------------

(def ^:private clause-emitters
  "Every emitting clause key and its emitter, (fn [ctx statement-map key])
   -> text. The emission order is per statement kind, in statement-kinds."
  {:with emit-with
   :with-recursive emit-with
   :select emit-select
   :from emit-from
   :join (emit-join "INNER JOIN")
   :inner-join (emit-join "INNER JOIN")
   :left-join (emit-join "LEFT JOIN")
   :right-join (emit-join "RIGHT JOIN")
   :full-join (emit-join "FULL JOIN")
   :cross-join emit-cross-join
   :where (condition "WHERE")
   :group-by emit-group-by
   :having (condition "HAVING")
   :union (emit-union "UNION")
   :union-all (emit-union "UNION ALL")
   :order-by emit-order-by
   :limit (emit-count "LIMIT" true)
   :offset (emit-count "OFFSET" false)
   :for emit-for
   :insert-into emit-insert-into
   :values emit-values
   :on-conflict emit-on-conflict
   :returning emit-returning
   :update emit-update
   :set emit-set
   :delete-from emit-delete-from
   :using emit-using
   :truncate emit-truncate
   :create-table emit-create-table
   :drop-table (emit-drop "TABLE")
   :alter-table emit-alter-table
   :create-index emit-create-index
   :drop-index (emit-drop "INDEX")
   :create-view emit-create-view
   :drop-view (emit-drop "VIEW")})

(def ^:private set-operation-clauses
  [:with :with-recursive :union :union-all :order-by :limit :offset])

(def ^:private statement-kinds
  "Per statement head: :order, the emitting clauses of that statement kind
   in emission order (the PostgreSQL synopsis order); :modifiers, the keys
   another clause's emitter consumes; :required, the clauses the head cannot
   do without; :inline-literals, true for data definition, whose literals
   are always spelled out (DDL takes no parameters). A key outside :order
   and :modifiers is not accepted next to that head."
  {:select {:order [:with :with-recursive :select :from :join :inner-join :left-join :right-join
                    :full-join :cross-join :where :group-by :having :order-by :limit :offset :for]
            :modifiers #{:distinct :distinct-on}}
   :union {:order set-operation-clauses}
   :union-all {:order set-operation-clauses}
   :insert-into {:order [:with :with-recursive :insert-into :values :on-conflict :returning]
                 :modifiers #{:columns}
                 :required [:values]}
   :update {:order [:with :with-recursive :update :set :from :where :returning]
            :required [:set]}
   :delete-from {:order [:with :with-recursive :delete-from :using :where :returning]}
   :truncate {:order [:truncate]
              :modifiers #{:restart-identity :continue-identity :cascade :restrict}}
   :create-table {:order [:create-table]
                  :modifiers #{:if-not-exists :columns :constraints}
                  :required [:columns]
                  :inline-literals true}
   :drop-table {:order [:drop-table]
                :modifiers #{:if-exists :cascade :restrict}
                :inline-literals true}
   :alter-table {:order [:alter-table]
                 :modifiers #{:if-exists :actions}
                 :required [:actions]
                 :inline-literals true}
   :create-index {:order [:create-index :where]
                  :modifiers #{:unique :concurrently :if-not-exists :on :using :columns}
                  :required [:on :columns]
                  :inline-literals true}
   :drop-index {:order [:drop-index]
                :modifiers #{:concurrently :if-exists :cascade :restrict}
                :inline-literals true}
   :create-view {:order [:create-view]
                 :modifiers #{:or-replace :columns :as}
                 :required [:as]
                 :inline-literals true}
   :drop-view {:order [:drop-view]
               :modifiers #{:if-exists :cascade :restrict}
               :inline-literals true}})

(def ^:private heads
  "The statement heads, in the order the one-head check reports them."
  [:select :union :union-all :insert-into :update :delete-from :truncate
   :create-table :drop-table :alter-table :create-index :drop-index :create-view :drop-view])

(def ^:private clause-keys
  "Every clause key of every statement kind."
  (into (set (keys clause-emitters))
        (mapcat :modifiers)
        (vals statement-kinds)))

(defn- allowed-clauses
  [head]
  (let [kind (get statement-kinds head)]
    (into (set (:order kind)) (:modifiers kind))))

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
  (let [present-heads (filterv #(contains? m %) heads)
        head (first present-heads)
        kind (get statement-kinds head)
        allowed (allowed-clauses head)]
    (when-not (= 1 (count present-heads))
      (throw (u/format-error :statement m
                             (str "a statement has exactly one of " (pr-str heads) "; got "
                                  (pr-str present-heads)))))
    (when (and (contains? m :with) (contains? m :with-recursive))
      (throw (u/format-error :statement (select-keys m [:with :with-recursive])
                             ":with and :with-recursive cannot both be given")))
    (doseq [k (keys m)]
      (when-not (contains? allowed k)
        (throw (u/format-error head k
                               (str (pr-str k) " does not apply to " (pr-str head) "; next to "
                                    (pr-str head) " only " (pr-str (vec (sort allowed)))
                                    " are accepted"
                                    (when (contains? #{:union :union-all} head)
                                      ", the rest belong inside each operand"))
                               {:allowed (vec (sort allowed))}))))
    (doseq [k (:required kind)]
      (when-not (contains? m k)
        (throw (u/format-error head m
                               (str (pr-str head) " needs " (pr-str k) ", got " (pr-str (vec (keys m))))))))
    (let [ctx (if (:inline-literals kind) (assoc ctx :inline? true) ctx)]
      (str/join " "
                (reduce (fn [acc k]
                          (if (contains? m k)
                            (conj acc ((get clause-emitters k) ctx m k))
                            acc))
                        []
                        (:order kind))))))

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
     is SQL injection. Data definition statements are rendered this way in
     both modes: DDL takes no parameters, so their :values is always [].

   Anything malformed (a value where a name is required, a mixed-case or
   exotic entity, an unknown clause or operator, an empty :select, an
   UPDATE without :set, rows of :values with different shapes, ...) is a
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
