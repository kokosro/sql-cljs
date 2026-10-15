(ns sql-cljs.ddl-test
  "The golden battery of data definition: every CREATE TABLE, DROP TABLE,
   ALTER TABLE, CREATE INDEX, DROP INDEX, CREATE VIEW and DROP VIEW shape
   pinned to its exact SQL text. DDL carries no parameters, so every
   statement here has :values [] in both modes."
  (:require [cljs.test :refer [deftest is testing]]
            [sql-cljs.core :as sql]))

(defn- text [q] (:text (sql/format q)))

(defn- inline [q] (sql/format q {:inline true}))

;; ---------------------------------------------------------------------------
;; CREATE TABLE
;; ---------------------------------------------------------------------------

(deftest create-table-columns
  (testing "one column, the whole map"
    (is (= {:text "CREATE TABLE t (id SERIAL PRIMARY KEY)" :values []}
           (sql/format {:create-table :t :columns [[:id :serial :primary-key]]}))))
  (testing "the page's films table, with a named column constraint and a raw multi-word type"
    (is (= "CREATE TABLE films (code CHAR(5) CONSTRAINT firstkey PRIMARY KEY, title VARCHAR(40) NOT NULL, did INTEGER NOT NULL, date_prod DATE, kind VARCHAR(10), len interval hour to minute, CONSTRAINT production UNIQUE (date_prod))"
           (text {:create-table :films
                  :columns [[:code [:char 5] [:constraint :firstkey :primary-key]]
                            [:title [:varchar 40] :not-null]
                            [:did :integer :not-null]
                            [:date_prod :date]
                            [:kind [:varchar 10]]
                            [:len (sql/raw "interval hour to minute")]]
                  :constraints [[:constraint :production [:unique :date_prod]]]}))))
  (testing "a schema-qualified table, a raw table, a symbol spelling"
    (is (= "CREATE TABLE public.t (a TEXT)" (text {:create-table :public/t :columns [[:a :text]]})))
    (is (= "CREATE TABLE \"Users\" (a TEXT)" (text {:create-table (sql/raw "\"Users\"") :columns [[:a :text]]})))
    (is (= "CREATE TABLE t (a TEXT NOT NULL)" (text '{:create-table t :columns [[a text not-null]]}))))
  (testing "a raw column name"
    (is (= "CREATE TABLE t (\"UserId\" INT)" (text {:create-table :t :columns [[(sql/raw "\"UserId\"") :int]]}))))
  (testing "IF NOT EXISTS"
    (is (= "CREATE TABLE IF NOT EXISTS t (a TEXT)" (text {:create-table :t :if-not-exists true :columns [[:a :text]]})))
    (is (= "CREATE TABLE t (a TEXT)" (text {:create-table :t :if-not-exists false :columns [[:a :text]]})))
    (is (= "CREATE TABLE t (a TEXT)" (text {:create-table :t :if-not-exists nil :columns [[:a :text]]})))))

(deftest create-table-types
  (testing "a keyword is its upper-case word"
    (is (= "CREATE TABLE t (a BIGINT, b SERIAL, c TEXT, d BOOLEAN, e TIMESTAMPTZ, f UUID, g JSONB)"
           (text {:create-table :t :columns [[:a :bigint] [:b :serial] [:c :text] [:d :boolean] [:e :timestamptz] [:f :uuid] [:g :jsonb]]}))))
  (testing "a vector is NAME(args)"
    (is (= "CREATE TABLE t (a VARCHAR(255), b NUMERIC(10, 2), c CHAR(5), d TIMESTAMP(3))"
           (text {:create-table :t :columns [[:a [:varchar 255]] [:b [:numeric 10 2]] [:c [:char 5]] [:d [:timestamp 3]]]}))))
  (testing "multi-word types: hyphenated keywords become space-separated words"
    (is (= "CREATE TABLE t (a DOUBLE PRECISION, b TIMESTAMP WITH TIME ZONE, c TIME WITHOUT TIME ZONE)"
           (text {:create-table :t :columns [[:a :double-precision] [:b :timestamp-with-time-zone] [:c :time-without-time-zone]]}))))
  (testing "multi-word and array types through raw, verbatim"
    (is (= "CREATE TABLE t (a timestamp with time zone, b text[], c INT)"
           (text {:create-table :t :columns [[:a (sql/raw "timestamp with time zone")] [:b (sql/raw "text[]")] [:c :int]]}))))
  (testing "a schema-qualified type"
    (is (= "CREATE TABLE t (a PUBLIC.MOOD)" (text {:create-table :t :columns [[:a :public/mood]]})))))

(deftest create-table-column-constraints
  (testing "the keyword constraints, in the order written"
    (is (= "CREATE TABLE t (a TEXT NOT NULL UNIQUE)" (text {:create-table :t :columns [[:a :text :not-null :unique]]})))
    (is (= "CREATE TABLE t (a TEXT UNIQUE NOT NULL)" (text {:create-table :t :columns [[:a :text :unique :not-null]]})))
    (is (= "CREATE TABLE t (a TEXT NULL)" (text {:create-table :t :columns [[:a :text :null]]})))
    (is (= "CREATE TABLE t (a INT PRIMARY KEY)" (text {:create-table :t :columns [[:a :int :primary-key]]}))))
  (testing "DEFAULT with every literal kind, always inline, in parameter mode too"
    (is (= {:text "CREATE TABLE t (a TEXT DEFAULT 'x')" :values []}
           (sql/format {:create-table :t :columns [[:a :text [:default "x"]]]})))
    (is (= {:text "CREATE TABLE t (a TEXT DEFAULT 'x')" :values []}
           (inline {:create-table :t :columns [[:a :text [:default "x"]]]})))
    (is (= "CREATE TABLE t (a TEXT DEFAULT 'it''s')" (text {:create-table :t :columns [[:a :text [:default "it's"]]]})))
    (is (= "CREATE TABLE t (a INT DEFAULT 0, b BOOLEAN DEFAULT TRUE, c TEXT DEFAULT NULL, d NUMERIC DEFAULT 1.5)"
           (text {:create-table :t :columns [[:a :int [:default 0]] [:b :boolean [:default true]] [:c :text [:default nil]] [:d :numeric [:default 1.5]]]}))))
  (testing "DEFAULT with a function call, a raw fragment, a parenthesized operator expression"
    (is (= "CREATE TABLE t (a TIMESTAMPTZ DEFAULT NOW())" (text {:create-table :t :columns [[:a :timestamptz [:default [:now]]]]})))
    (is (= "CREATE TABLE t (a TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)" (text {:create-table :t :columns [[:a :timestamptz [:default (sql/raw "CURRENT_TIMESTAMP")]]]})))
    (is (= "CREATE TABLE t (a INT DEFAULT (1 + 2))" (text {:create-table :t :columns [[:a :int [:default [:+ 1 2]]]]})))
    (is (= "CREATE TABLE t (a INT DEFAULT -(1))" (text {:create-table :t :columns [[:a :int [:default [:- 1]]]]}))))
  (testing "DEFAULT renders a subquery as written; the server, not the formatter, rejects it"
    (is (= "CREATE TABLE t (a INT DEFAULT (SELECT MAX(id) FROM u))"
           (text {:create-table :t :columns [[:a :int [:default {:select [[[:max :id]]] :from [:u]}]]]}))))
  (testing "DEFAULT then NOT NULL, the usual order"
    (is (= "CREATE TABLE t (a INT DEFAULT 0 NOT NULL)" (text {:create-table :t :columns [[:a :int [:default 0] :not-null]]}))))
  (testing "CHECK is always parenthesized; a nested operator expression inside"
    (is (= "CREATE TABLE t (a INT CHECK (a > 0))" (text {:create-table :t :columns [[:a :int [:check [:> :a 0]]]]})))
    (is (= "CREATE TABLE t (a INT CHECK ((a > 0) AND (a < 10)))"
           (text {:create-table :t :columns [[:a :int [:check [:and [:> :a 0] [:< :a 10]]]]]})))
    (is (= "CREATE TABLE t (name VARCHAR(40) NOT NULL CHECK (name <> ''))"
           (text {:create-table :t :columns [[:name [:varchar 40] :not-null [:check [:<> :name ""]]]]})))
    (is (= "CREATE TABLE t (a INT CHECK (a IN (1, 2)))" (text {:create-table :t :columns [[:a :int [:check [:in :a [1 2]]]]]})))
    (is (= "CREATE TABLE t (a INT CHECK (a > 0))" (text {:create-table :t :columns [[:a :int [:check (sql/raw "a > 0")]]]}))))
  (testing "REFERENCES with and without the referenced column"
    (is (= "CREATE TABLE t (did INTEGER REFERENCES distributors)" (text {:create-table :t :columns [[:did :integer [:references :distributors]]]})))
    (is (= "CREATE TABLE t (did INTEGER REFERENCES distributors (did))" (text {:create-table :t :columns [[:did :integer [:references :distributors :did]]]})))
    (is (= "CREATE TABLE t (did INTEGER REFERENCES public.d (a, b))" (text {:create-table :t :columns [[:did :integer [:references :public/d :a :b]]]}))))
  (testing "REFERENCES with every ON DELETE / ON UPDATE action and their combinations"
    (is (= "CREATE TABLE t (d INT REFERENCES d (id) ON DELETE CASCADE)"
           (text {:create-table :t :columns [[:d :int [:references :d :id] [:on-delete :cascade]]]})))
    (is (= "CREATE TABLE t (d INT REFERENCES d (id) ON UPDATE CASCADE)"
           (text {:create-table :t :columns [[:d :int [:references :d :id] [:on-update :cascade]]]})))
    (is (= "CREATE TABLE t (d INT REFERENCES d (id) ON DELETE SET NULL ON UPDATE RESTRICT)"
           (text {:create-table :t :columns [[:d :int [:references :d :id] [:on-delete :set-null] [:on-update :restrict]]]})))
    (is (= "CREATE TABLE t (d INT REFERENCES d (id) ON UPDATE SET DEFAULT ON DELETE NO ACTION)"
           (text {:create-table :t :columns [[:d :int [:references :d :id] [:on-update :set-default] [:on-delete :no-action]]]})))
    (is (= "CREATE TABLE t (d INT REFERENCES d ON DELETE CASCADE NOT NULL)"
           (text {:create-table :t :columns [[:d :int [:references :d] [:on-delete :cascade] :not-null]]}))))
  (testing "a named column constraint, alone and with the actions after a named REFERENCES"
    (is (= "CREATE TABLE t (a INT CONSTRAINT a_positive CHECK (a > 0))"
           (text {:create-table :t :columns [[:a :int [:constraint :a_positive [:check [:> :a 0]]]]]})))
    (is (= "CREATE TABLE t (a INT CONSTRAINT nn NOT NULL)" (text {:create-table :t :columns [[:a :int [:constraint :nn :not-null]]]})))
    (is (= "CREATE TABLE t (d INT CONSTRAINT fk REFERENCES d (id) ON DELETE SET NULL)"
           (text {:create-table :t :columns [[:d :int [:constraint :fk [:references :d :id]] [:on-delete :set-null]]]}))))
  (testing "a raw constraint is spliced verbatim"
    (is (= "CREATE TABLE t (id INT GENERATED ALWAYS AS IDENTITY PRIMARY KEY)"
           (text {:create-table :t :columns [[:id :int (sql/raw "GENERATED ALWAYS AS IDENTITY") :primary-key]]}))))
  (testing "the symbol spelling of constraints"
    (is (= "CREATE TABLE t (a INT NOT NULL DEFAULT 0 CHECK (a > 0))"
           (text '{:create-table t :columns [[a int not-null [default 0] [check [> a 0]]]]})))))

(deftest create-table-table-constraints
  (testing "PRIMARY KEY, UNIQUE, CHECK, FOREIGN KEY"
    (is (= "CREATE TABLE t (a INT, b INT, PRIMARY KEY (a, b))" (text {:create-table :t :columns [[:a :int] [:b :int]] :constraints [[:primary-key :a :b]]})))
    (is (= "CREATE TABLE t (a INT, UNIQUE (a))" (text {:create-table :t :columns [[:a :int]] :constraints [[:unique :a]]})))
    (is (= "CREATE TABLE t (a INT, b INT, UNIQUE (a, b))" (text {:create-table :t :columns [[:a :int] [:b :int]] :constraints [[:unique :a :b]]})))
    (is (= "CREATE TABLE t (did INT, CHECK (did > 100))" (text {:create-table :t :columns [[:did :int]] :constraints [[:check [:> :did 100]]]})))
    (is (= "CREATE TABLE t (d INT, FOREIGN KEY (d) REFERENCES e (id))"
           (text {:create-table :t :columns [[:d :int]] :constraints [[:foreign-key :d [:references :e :id]]]})))
    (is (= "CREATE TABLE t (d INT, FOREIGN KEY (d) REFERENCES e)"
           (text {:create-table :t :columns [[:d :int]] :constraints [[:foreign-key [:d] [:references :e]]]}))))
  (testing "FOREIGN KEY with several columns and the action combinations"
    (is (= "CREATE TABLE t (a INT, b INT, FOREIGN KEY (a, b) REFERENCES e (c, d) ON DELETE CASCADE ON UPDATE SET DEFAULT)"
           (text {:create-table :t :columns [[:a :int] [:b :int]]
                  :constraints [[:foreign-key [:a :b] [:references :e :c :d] [:on-delete :cascade] [:on-update :set-default]]]})))
    (is (= "CREATE TABLE t (a INT, FOREIGN KEY (a) REFERENCES e (c) ON UPDATE NO ACTION)"
           (text {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references :e :c] [:on-update :no-action]]]})))
    (is (= "CREATE TABLE t (a INT, FOREIGN KEY (a) REFERENCES e (c) ON DELETE RESTRICT)"
           (text {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references :e :c] [:on-delete :restrict]]]})))
    (is (= "CREATE TABLE t (a INT, FOREIGN KEY (a) REFERENCES e (c) ON DELETE SET NULL)"
           (text {:create-table :t :columns [[:a :int]] :constraints [[:foreign-key :a [:references :e :c] [:on-delete :set-null]]]}))))
  (testing "named table constraints, the page's examples"
    (is (= "CREATE TABLE films (code INT, title TEXT, CONSTRAINT code_title PRIMARY KEY (code, title))"
           (text {:create-table :films :columns [[:code :int] [:title :text]] :constraints [[:constraint :code_title [:primary-key :code :title]]]})))
    (is (= "CREATE TABLE distributors (did INTEGER, name VARCHAR(40), CONSTRAINT con1 CHECK ((did > 100) AND (name <> '')))"
           (text {:create-table :distributors :columns [[:did :integer] [:name [:varchar 40]]]
                  :constraints [[:constraint :con1 [:check [:and [:> :did 100] [:<> :name ""]]]]]})))
    (is (= "CREATE TABLE t (d INT, CONSTRAINT t_d_fkey FOREIGN KEY (d) REFERENCES e (id) ON DELETE CASCADE)"
           (text {:create-table :t :columns [[:d :int]] :constraints [[:constraint :t_d_fkey [:foreign-key :d [:references :e :id] [:on-delete :cascade]]]]}))))
  (testing "several constraints, in order, after every column; a raw constraint"
    (is (= "CREATE TABLE t (a INT, b INT, PRIMARY KEY (a), UNIQUE (b), CHECK (b > a), EXCLUDE USING gist (a WITH =))"
           (text {:create-table :t :columns [[:a :int] [:b :int]]
                  :constraints [[:primary-key :a] [:unique :b] [:check [:> :b :a]] (sql/raw "EXCLUDE USING gist (a WITH =)")]}))))
  (testing "a single constraint vector in place of the vector of them"
    (is (= "CREATE TABLE t (a INT, PRIMARY KEY (a))" (text {:create-table :t :columns [[:a :int]] :constraints [[:primary-key :a]]})))))

;; ---------------------------------------------------------------------------
;; DROP TABLE
;; ---------------------------------------------------------------------------

(deftest drop-table
  (is (= {:text "DROP TABLE t" :values []} (sql/format {:drop-table :t})))
  (is (= "DROP TABLE films, distributors" (text {:drop-table [:films :distributors]})))
  (is (= "DROP TABLE IF EXISTS t" (text {:drop-table :t :if-exists true})))
  (is (= "DROP TABLE t CASCADE" (text {:drop-table :t :cascade true})))
  (is (= "DROP TABLE t RESTRICT" (text {:drop-table :t :restrict true})))
  (is (= "DROP TABLE IF EXISTS t, u CASCADE" (text {:drop-table [:t :u] :if-exists true :cascade true})))
  (is (= "DROP TABLE IF EXISTS public.t RESTRICT" (text {:drop-table :public/t :if-exists true :restrict true})))
  (is (= "DROP TABLE t" (text {:drop-table :t :if-exists false :cascade nil :restrict false})))
  (is (= "DROP TABLE \"Users\"" (text {:drop-table (sql/raw "\"Users\"")})))
  (is (= "DROP TABLE t" (text '{:drop-table t}))))

;; ---------------------------------------------------------------------------
;; ALTER TABLE
;; ---------------------------------------------------------------------------

(deftest alter-table
  (testing "ADD COLUMN, the page's example, with constraints, IF NOT EXISTS"
    (is (= {:text "ALTER TABLE distributors ADD COLUMN address VARCHAR(30)" :values []}
           (sql/format {:alter-table :distributors :actions [[:add-column [:address [:varchar 30]]]]})))
    (is (= "ALTER TABLE t ADD COLUMN x INT NOT NULL DEFAULT 0 REFERENCES u (id) ON DELETE CASCADE"
           (text {:alter-table :t :actions [[:add-column [:x :int :not-null [:default 0] [:references :u :id] [:on-delete :cascade]]]]})))
    (is (= "ALTER TABLE t ADD COLUMN IF NOT EXISTS x INT" (text {:alter-table :t :actions [[:add-column [:x :int] :if-not-exists]]})))
    (is (= "ALTER TABLE t ADD COLUMN x TEXT DEFAULT 'a'" (text {:alter-table :t :actions [[:add-column [:x :text [:default "a"]]]]}))))
  (testing "DROP COLUMN, the page's example, IF EXISTS, CASCADE / RESTRICT"
    (is (= "ALTER TABLE distributors DROP COLUMN address RESTRICT" (text {:alter-table :distributors :actions [[:drop-column :address :restrict]]})))
    (is (= "ALTER TABLE t DROP COLUMN a" (text {:alter-table :t :actions [[:drop-column :a]]})))
    (is (= "ALTER TABLE t DROP COLUMN IF EXISTS a CASCADE" (text {:alter-table :t :actions [[:drop-column :a :if-exists :cascade]]})))
    (is (= "ALTER TABLE t DROP COLUMN IF EXISTS a" (text {:alter-table :t :actions [[:drop-column :a :if-exists]]})))
    (is (= "ALTER TABLE t DROP COLUMN IF EXISTS a CASCADE" (text {:alter-table :t :actions [[:drop-column :a :cascade :if-exists]]}))))
  (testing "RENAME COLUMN, the page's example"
    (is (= "ALTER TABLE distributors RENAME COLUMN address TO city" (text {:alter-table :distributors :actions [[:rename-column :address :city]]}))))
  (testing "several ADD / DROP actions are comma-separated, in order"
    (is (= "ALTER TABLE t ADD COLUMN a INT, ADD COLUMN IF NOT EXISTS b TEXT, DROP COLUMN c, DROP COLUMN IF EXISTS d"
           (text {:alter-table :t :actions [[:add-column [:a :int]] [:add-column [:b :text] :if-not-exists] [:drop-column :c] [:drop-column :d :if-exists]]})))
    (is (= "ALTER TABLE t DROP COLUMN c, ADD COLUMN a INT" (text {:alter-table :t :actions [[:drop-column :c] [:add-column [:a :int]]]}))))
  (testing "IF EXISTS on the table, a schema-qualified table, a raw table, symbols"
    (is (= "ALTER TABLE IF EXISTS t DROP COLUMN a" (text {:alter-table :t :if-exists true :actions [[:drop-column :a]]})))
    (is (= "ALTER TABLE public.t DROP COLUMN a" (text {:alter-table :public/t :actions [[:drop-column :a]]})))
    (is (= "ALTER TABLE \"T\" DROP COLUMN \"A\"" (text {:alter-table (sql/raw "\"T\"") :actions [[:drop-column (sql/raw "\"A\"")]]})))
    (is (= "ALTER TABLE t ADD COLUMN IF NOT EXISTS a INT" (text '{:alter-table t :actions [[add-column [a int] if-not-exists]]})))))

;; ---------------------------------------------------------------------------
;; CREATE INDEX
;; ---------------------------------------------------------------------------

(deftest create-index
  (testing "plain, the page's examples"
    (is (= {:text "CREATE INDEX title_idx ON films (title)" :values []}
           (sql/format {:create-index :title_idx :on :films :columns [:title]})))
    (is (= "CREATE UNIQUE INDEX title_idx ON films (title)" (text {:create-index :title_idx :unique true :on :films :columns [:title]})))
    (is (= "CREATE INDEX CONCURRENTLY sales_quantity_index ON sales_table (quantity)"
           (text {:create-index :sales_quantity_index :concurrently true :on :sales_table :columns [:quantity]}))))
  (testing "an unnamed index, a single column keyword, a schema-qualified table"
    (is (= "CREATE INDEX ON films (title)" (text {:create-index nil :on :films :columns [:title]})))
    (is (= "CREATE INDEX i ON t (a)" (text {:create-index :i :on :t :columns :a})))
    (is (= "CREATE INDEX i ON public.t (a, b)" (text {:create-index :i :on :public/t :columns [:a :b]}))))
  (testing "USING method between the table and the columns; the method as a one-item vector or raw"
    (is (= "CREATE INDEX i ON t USING gin (a)" (text {:create-index :i :on :t :using :gin :columns [:a]})))
    (is (= "CREATE INDEX i ON t USING gin (a)" (text {:create-index :i :on :t :using [:gin] :columns [:a]})))
    (is (= "CREATE INDEX i ON t USING bloom (a)" (text {:create-index :i :on :t :using (sql/raw "bloom") :columns [:a]}))))
  (testing "per-column ASC / DESC / NULLS, an expression column, an operator column parenthesized"
    (is (= "CREATE INDEX i ON t (a, b DESC, c ASC NULLS LAST, d DESC NULLS FIRST)"
           (text {:create-index :i :on :t :columns [:a [:b :desc] [:c :asc :nulls-last] [:d :desc :nulls-first]]})))
    (is (= "CREATE INDEX i ON t (LOWER(title))" (text {:create-index :i :on :t :columns [[[:lower :title]]]})))
    (is (= "CREATE INDEX i ON t (LOWER(title) DESC)" (text {:create-index :i :on :t :columns [[[:lower :title] :desc]]})))
    (is (= "CREATE INDEX i ON t ((a + b) DESC, (a * 2))" (text {:create-index :i :on :t :columns [[[:+ :a :b] :desc] [[:* :a 2]]]})))
    (is (= "CREATE INDEX i ON t ((data ->> 'x'))" (text {:create-index :i :on :t :columns [(sql/raw "(data ->> 'x')")]}))))
  (testing "a partial index: WHERE last, inline in both modes"
    (is (= {:text "CREATE INDEX i ON t (a) WHERE kind = 'x'" :values []}
           (sql/format {:create-index :i :on :t :columns [:a] :where [:= :kind "x"]})))
    (is (= "CREATE INDEX i ON t (a) WHERE (deleted_at IS NULL) AND (n > 1)"
           (text {:create-index :i :on :t :columns [:a] :where [:and [:is-null :deleted_at] [:> :n 1]]})))
    (is (= "CREATE INDEX i ON t (a) WHERE active" (text {:create-index :i :on :t :columns [:a] :where (sql/raw "active")}))))
  (testing "IF NOT EXISTS, and every option together in the synopsis order"
    (is (= "CREATE INDEX IF NOT EXISTS i ON t (a)" (text {:create-index :i :if-not-exists true :on :t :columns [:a]})))
    (is (= "CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS i ON t USING btree (a DESC) WHERE a > 0"
           (text {:create-index :i :unique true :concurrently true :if-not-exists true :on :t :using :btree :columns [[:a :desc]] :where [:> :a 0]}))))
  (testing "false flags are absent; a raw index name; the symbol spelling"
    (is (= "CREATE INDEX i ON t (a)" (text {:create-index :i :unique false :concurrently nil :if-not-exists false :on :t :columns [:a]})))
    (is (= "CREATE INDEX \"Idx\" ON t (a)" (text {:create-index (sql/raw "\"Idx\"") :on :t :columns [:a]})))
    (is (= "CREATE INDEX i ON t (a DESC)" (text '{:create-index i :on t :columns [[a desc]]})))))

;; ---------------------------------------------------------------------------
;; DROP INDEX
;; ---------------------------------------------------------------------------

(deftest drop-index
  (is (= {:text "DROP INDEX title_idx" :values []} (sql/format {:drop-index :title_idx})))
  (is (= "DROP INDEX i, j" (text {:drop-index [:i :j]})))
  (is (= "DROP INDEX IF EXISTS i" (text {:drop-index :i :if-exists true})))
  (is (= "DROP INDEX CONCURRENTLY i" (text {:drop-index :i :concurrently true})))
  (is (= "DROP INDEX CONCURRENTLY IF EXISTS i" (text {:drop-index :i :concurrently true :if-exists true})))
  (is (= "DROP INDEX CONCURRENTLY i RESTRICT" (text {:drop-index :i :concurrently true :restrict true})))
  (is (= "DROP INDEX CONCURRENTLY IF EXISTS public.i" (text {:drop-index [:public/i] :concurrently true :if-exists true})))
  (is (= "DROP INDEX i CASCADE" (text {:drop-index :i :cascade true})))
  (is (= "DROP INDEX IF EXISTS i, j CASCADE" (text {:drop-index [:i :j] :if-exists true :cascade true})))
  (is (= "DROP INDEX i RESTRICT" (text {:drop-index :i :restrict true})))
  (is (= "DROP INDEX i" (text {:drop-index :i :concurrently false :if-exists nil :cascade false})))
  (is (= "DROP INDEX \"Idx\"" (text {:drop-index (sql/raw "\"Idx\"")}))))

;; ---------------------------------------------------------------------------
;; CREATE VIEW / DROP VIEW
;; ---------------------------------------------------------------------------

(deftest create-view
  (testing "the page's example: the body is rendered without parentheses and its values inline, in parameter mode"
    (is (= {:text "CREATE VIEW comedies AS SELECT * FROM films WHERE kind = 'Comedy'" :values []}
           (sql/format {:create-view :comedies :as {:select [:*] :from [:films] :where [:= :kind "Comedy"]}}))))
  (testing "OR REPLACE, a column list, both"
    (is (= "CREATE OR REPLACE VIEW v AS SELECT a FROM t" (text {:create-view :v :or-replace true :as {:select [:a] :from [:t]}})))
    (is (= "CREATE VIEW v (x, y) AS SELECT a, b FROM t" (text {:create-view :v :columns [:x :y] :as {:select [:a :b] :from [:t]}})))
    (is (= "CREATE OR REPLACE VIEW public.v (x) AS SELECT a FROM t" (text {:create-view :public/v :or-replace true :columns :x :as {:select [:a] :from [:t]}})))
    (is (= "CREATE VIEW v AS SELECT a FROM t" (text {:create-view :v :or-replace false :as {:select [:a] :from [:t]}}))))
  (testing "the body as raw, as a set operation, with a CTE, with a nested value"
    (is (= "CREATE VIEW v AS SELECT 1" (text {:create-view :v :as (sql/raw "SELECT 1")})))
    (is (= "CREATE VIEW v AS (SELECT a FROM t) UNION (SELECT a FROM u)" (text {:create-view :v :as {:union [{:select [:a] :from [:t]} {:select [:a] :from [:u]}]}})))
    (is (= "CREATE VIEW v AS WITH c AS (SELECT a FROM t WHERE a > 1) SELECT a FROM c"
           (text {:create-view :v :as {:with [[:c {:select [:a] :from [:t] :where [:> :a 1]}]] :select [:a] :from [:c]}})))
    (is (= {:text "CREATE VIEW v AS SELECT a FROM t WHERE a IN (SELECT b FROM u WHERE c = 'x') LIMIT 10" :values []}
           (sql/format {:create-view :v :as {:select [:a] :from [:t] :where [:in :a {:select [:b] :from [:u] :where [:= :c "x"]}] :limit 10}}))))
  (testing "a raw view name, the symbol spelling"
    (is (= "CREATE VIEW \"V\" AS SELECT a FROM t" (text {:create-view (sql/raw "\"V\"") :as {:select [:a] :from [:t]}})))
    (is (= "CREATE VIEW v AS SELECT a FROM t" (text '{:create-view v :as {:select [a] :from [t]}})))))

(deftest drop-view
  (is (= {:text "DROP VIEW kinds" :values []} (sql/format {:drop-view :kinds})))
  (is (= "DROP VIEW v, w" (text {:drop-view [:v :w]})))
  (is (= "DROP VIEW IF EXISTS v" (text {:drop-view :v :if-exists true})))
  (is (= "DROP VIEW v CASCADE" (text {:drop-view :v :cascade true})))
  (is (= "DROP VIEW v RESTRICT" (text {:drop-view :v :restrict true})))
  (is (= "DROP VIEW IF EXISTS v, w CASCADE" (text {:drop-view [:v :w] :if-exists true :cascade true})))
  (is (= "DROP VIEW IF EXISTS public.v" (text {:drop-view :public/v :if-exists true})))
  (is (= "DROP VIEW v" (text {:drop-view :v :if-exists false :cascade nil}))))

;; ---------------------------------------------------------------------------
;; Inline mode: DDL is always inline, so both modes agree
;; ---------------------------------------------------------------------------

(deftest ddl-is-inline-in-both-modes
  (doseq [q [{:create-table :t :columns [[:a :text [:default "x"]] [:b :int [:check [:> :b 0]]]] :constraints [[:check [:<> :a "y"]]]}
             {:create-table :t :if-not-exists true :columns [[:id :serial :primary-key] [:d :int [:references :u :id] [:on-delete :cascade]]]}
             {:drop-table [:t :u] :if-exists true :cascade true}
             {:alter-table :t :actions [[:add-column [:x :text [:default "a"] [:check [:in :x ["a" "b"]]]]]]}
             {:create-index :i :unique true :on :t :columns [[:a :desc]] :where [:and [:= :kind "x"] [:> :n 1.5]]}
             {:drop-index :i :concurrently true}
             {:create-view :v :or-replace true :as {:select [:a] :from [:t] :where [:= :a "x"] :limit 5}}
             {:drop-view :v :if-exists true}]]
    (is (= (sql/format q) (inline q)))
    (is (= [] (:values (sql/format q))))))
