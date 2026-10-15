(ns sql-cljs.core
  "The sql-cljs query builder for PostgreSQL: plain ClojureScript data in, a
   parameterized SQL query out. A statement is a map of clauses
   ({:select [:a :b] :from [:foo] :where [:= :foo.a \"baz\"]}), an expression
   is a vector whose first element is the operator or function, keywords and
   symbols name tables and columns, and strings are always values. `format`
   renders such a map to {:text \"SELECT a, b FROM foo WHERE foo.a = $1\"
   :values [\"baz\"]}, the exact shape pg-cljs.core/query! takes, with the
   values passed through untouched. The library is pure formatting: no JS
   interop, no runtime npm dependency, no network.")

(def api-version
  "The library's own version marker, as a string. It names the version of
   the sql-cljs public surface."
  "0.1.0")
