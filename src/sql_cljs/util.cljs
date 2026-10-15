(ns sql-cljs.util
  "Engine internals shared by every sql-cljs formatter: the one error
   constructor of the :sql-cljs/format-error taxonomy, the identifier
   validator every entity goes through, the raw escape-hatch type, and the
   literal renderer of inline mode. Pure ClojureScript: strings, keywords,
   vectors and maps only; no I/O, nothing asynchronous.

   Nothing here is meant for callers: sql-cljs.core/format, sql-cljs.core/raw
   and sql-cljs.core/api-version are the public surface. The clause emitters
   of every statement kind (queries, data modification, data definition)
   build on these helpers so that the entity rule, the error shape and the
   literal spellings are decided in exactly one place each."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Errors: ONE taxonomy, :type :sql-cljs/format-error
;; ---------------------------------------------------------------------------

(defn format-error
  "ex-info of :type :sql-cljs/format-error, the one error sql-cljs raises:
   the statement, a clause or an expression is malformed, detected before
   any text is produced. Its message starts with \"sql-cljs: \"; its data is
   {:type :sql-cljs/format-error :message message :op op :arg arg} merged
   with `data`. :op names the clause or operator the offending piece belongs
   to (:select, :where, :in, :format, ...), :arg is the offending piece
   itself (often the whole form); `data` adds :entity (the whole entity
   whose part failed) or :allowed (the closed set of accepted values) where
   one exists."
  [op arg message & [data]]
  (ex-info (str "sql-cljs: " message)
           (merge {:type :sql-cljs/format-error :message message :op op :arg arg}
                  data)))

;; ---------------------------------------------------------------------------
;; raw: a SQL fragment spliced verbatim
;; ---------------------------------------------------------------------------

(defprotocol IRaw
  "A fragment of SQL text the formatter copies into the output as is."
  (raw-sql [this] "The verbatim SQL text of a raw fragment."))

(deftype Raw [sql]
  IRaw
  (raw-sql [_] sql)
  IEquiv
  (-equiv [_ other] (and (instance? Raw other) (= sql (raw-sql other))))
  IHash
  (-hash [_] (hash sql))
  IPrintWithWriter
  (-pr-writer [_ writer _] (-write writer (str "#sql-cljs/raw " (pr-str sql)))))

(defn raw?
  "True when x is a raw fragment made by sql-cljs.core/raw."
  [x]
  (instance? Raw x))

(defn make-raw
  "The raw fragment of the string `sql`; anything but a string is a
   format-error with :op op."
  [op sql]
  (if (string? sql)
    (Raw. sql)
    (throw (format-error op sql
                         (str "raw takes exactly one string, the SQL text to splice verbatim; got "
                              (pr-str sql))))))

;; ---------------------------------------------------------------------------
;; Entities: keywords and symbols naming tables, columns, aliases, functions
;; ---------------------------------------------------------------------------

(defn entity?
  "True when x is a keyword or a symbol: an entity (a name), never a value."
  [x]
  (or (keyword? x) (symbol? x)))

(def identifier-pattern
  "What one dot-separated part of an entity must match to be emitted bare:
   a lower-case letter or underscore, then lower-case letters, digits,
   underscores and dollar signs (the unquoted-identifier charset of
   PostgreSQL, lower case only). PostgreSQL folds every unquoted name to
   lower case, so a bare name outside this set would name a different
   object than it reads; such names go through the raw escape hatch."
  #"^[a-z_][a-z0-9_$]*$")

(defn entity-parts
  "The parts of an entity, split on . and / (the namespace of :foo/bar is
   its first part): :foo/bar -> [\"foo\" \"bar\"], :a.b -> [\"a\" \"b\"],
   :foo/* -> [\"foo\" \"*\"]. Empty parts are kept so the validator can
   reject them."
  [x]
  (str/split (if-let [n (namespace x)]
               (str n "." (name x))
               (name x))
             #"[./]" -1))

(defn star?
  "True when the entity x ends in the star element: :* or :t/* or :t.*."
  [x]
  (and (entity? x) (= "*" (peek (entity-parts x)))))

(defn entity-name
  "The SQL text of the entity x, parts joined with dots, never quoted:
   :foo/bar -> \"foo.bar\". Every part must match identifier-pattern;
   otherwise a format-error with :op op, :arg the offending part and
   :entity x, whose message names the raw escape hatch. The star element
   is accepted as the last part only when star? is true (select list and
   function arguments); a non-entity x is a format-error with :arg x."
  [op x star?]
  (when-not (entity? x)
    (throw (format-error op x
                         (str "expected a keyword or symbol naming a table or column, got "
                              (pr-str x)
                              (when (string? x) " (strings are values, never names)")))))
  (let [parts (entity-parts x)
        last-i (dec (count parts))]
    (doseq [[i part] (map-indexed vector parts)]
      (cond
        (= "*" part)
        (when-not (and star? (= i last-i))
          (throw (format-error op part
                               (str "the star element " (pr-str x)
                                    " is only accepted in the select list and as a function argument"
                                    " ([:count :*]); here a plain name is required")
                               {:entity x})))

        (re-matches identifier-pattern part)
        nil

        :else
        (throw (format-error op part
                             (str (pr-str part) " in " (pr-str x)
                                  " is not a plain identifier (a lower-case letter or _ followed by"
                                  " lower-case letters, digits, _ or $); mixed-case, quoted or exotic"
                                  " names go through the raw escape hatch, e.g."
                                  " (sql-cljs.core/raw \"\\\"UserId\\\"\")")
                             {:entity x}))))
    (str/join "." parts)))

(defn operator-shaped?
  "True when the keyword or symbol k has no letter in its name (:<=>, :!=,
   :||): such a key is an operator and must be one the formatter knows;
   it is never a function name."
  [k]
  (nil? (re-find #"[a-zA-Z]" (name k))))

;; ---------------------------------------------------------------------------
;; Literals: inline mode
;; ---------------------------------------------------------------------------

(defn- bigint?*
  [x]
  (identical? "bigint" (goog/typeOf x)))

(defn literal
  "The SQL literal of the value x for inline mode: nil -> NULL, a string ->
   '...' with every ' doubled, true/false -> TRUE/FALSE, a number -> its
   decimal text (-0 renders 0), a BigInt -> its decimal text. NaN and
   ±Infinity are not numeric constants and any other value (an object, a
   function, a date) has no literal spelling the library can vouch for:
   format-error with :op :inline and :arg x."
  [x]
  (cond
    (nil? x) "NULL"
    (string? x) (str "'" (str/replace x "'" "''") "'")
    (true? x) "TRUE"
    (false? x) "FALSE"
    (number? x) (if (or (NaN? x) (infinite? x))
                  (throw (format-error :inline x
                                       (str (str x) " is not a SQL numeric constant and cannot be"
                                            " inlined; pass it as a parameter (no :inline) or as a string")))
                  (str x))
    (bigint?* x) (str x)
    :else (throw (format-error :inline x
                               (str "no SQL literal spelling for " (pr-str x)
                                    " in inline mode; only nil, strings, booleans, numbers and"
                                    " BigInts are inlined (stringify or cast the value first)")))))
