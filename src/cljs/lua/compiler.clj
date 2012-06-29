(ns cljs.lua.compiler
  (:refer-clojure :exclude [munge])
  (:require [cljs.analyzer :as ana]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [cljs.tagged-literals :as tags]
            [clojure.data.json :as json])
  (:import java.lang.StringBuilder
           java.io.PrintWriter))

(def ^:dynamic *position* nil)
(def ^:dynamic *finalizer* nil)
(def ^:dynamic *loop-var* nil)
(def ^:dynamic *emit-comments* false)

(defn in-expr? [env]
  (= :expr (:context env)))

(def lua-reserved
  #{})

(def cljs-reserved-file-names #{"deps.cljs"})

(defn munge
  ([s] (munge s lua-reserved))
  ([s reserved]
    (let [ss (string/replace (str s) #"\/(.)" ".$1") ; Division is special
          ss (apply str (map #(if (reserved %) (str % "$") %)
                             (string/split ss #"(?<=\.)|(?=\.)")))
          ms (clojure.lang.Compiler/munge ss)]
      (if (symbol? s)
        (symbol ms)
        ms))))

(defn- comma-sep [xs]
  (interpose "," xs))

(defn- escape-char [^Character c]
  c)
(comment
  (let [cp (.hashCode c)]
    (case cp
      ; Handle printable escapes before ASCII
      34 "\\\""
      92 "\\\\"
      ; Handle non-printable escapes
      8 "\\b"
      12 "\\f"
      10 "\\n"
      13 "\\r"
      9 "\\t"
      (if (< 31 cp 127)
        c ; Print simple ASCII characters
        (format "\\u%04X" cp))))) ; Any other character is Unicode


(defn- escape-string [^CharSequence s]
  (let [sb (StringBuilder. (count s))]
    (doseq [c s]
      (.append sb (escape-char c)))
    (.toString sb)))

(defn- wrap-in-double-quotes [x]
  (str \" x \"))

(defmulti emit :op)

(defn emits [& xs]
  (doseq [x xs]
    (cond
      (nil? x) nil
      (map? x) (emit x)
      (seq? x) (apply emits x)
      (fn? x)  (x)
      :else (do
              (let [s (print-str x)]
                (when *position*
                  (swap! *position* (fn [[line column]]
                                      [line (+ column (count s))])))
                (print s)))))
  nil)

(defn ^String emit-str [expr]
  (with-out-str (emit expr)))

(defn emitln [& xs]
  (apply emits xs)
  ;; Prints column-aligned line number comments; good test of *position*.
  ;(when *position*
  ;  (let [[line column] @*position*]
  ;    (print (apply str (concat (repeat (- 120 column) \space) ["// " (inc line)])))))
  (println)
  (when *position*
    (swap! *position* (fn [[line column]]
                        [(inc line) 0])))
  nil)

(defmulti emit-constant class)
(defmethod emit-constant nil [x] (emits "nil"))
(defmethod emit-constant Long [x] (emits x))
(defmethod emit-constant Integer [x] (emits x)) ; reader puts Integers in metadata
(defmethod emit-constant Double [x] (emits x))
(defmethod emit-constant String [x]
  (emits (wrap-in-double-quotes (escape-string x))))
(defmethod emit-constant Boolean [x] (emits (if x "true" "false")))
(defmethod emit-constant Character [x]
  (emits (wrap-in-double-quotes (escape-char x))))

(defmethod emit-constant java.util.regex.Pattern [x]
  (let [[_ flags pattern] (re-find #"^(?:\(\?([idmsux]*)\))?(.*)" (str x))]
    (emits \/ (.replaceAll (re-matcher #"/" pattern) "\\\\/") \/ flags)))

(defmethod emit-constant clojure.lang.Keyword [x]
           (emits \" "\\239\\183\\144" \'
                  (if (namespace x)
                    (str (namespace x) "/") "")
                  (name x)
                  \"))

(defmethod emit-constant clojure.lang.Symbol [x]
           (emits \" "\\uFDD1" \'
                  (if (namespace x)
                    (str (namespace x) "/") "")
                  (name x)
                  \"))

(defn- emit-meta-constant [x & body]
  (if (meta x)
    (do
      (emits "cljs.core.with_meta(" body ",")
      (emit-constant (meta x))
      (emits ")"))
    (emits body)))

(defmethod emit-constant clojure.lang.PersistentList$EmptyList [x]
  (emit-meta-constant x "cljs.core.List.EMPTY"))

(defmethod emit-constant clojure.lang.PersistentList [x]
  (emit-meta-constant x
    (concat ["cljs.core.list("]
            (comma-sep (map #(fn [] (emit-constant %)) x))
            [")"])))

(defmethod emit-constant clojure.lang.Cons [x]
  (emit-meta-constant x
    (concat ["cljs.core.list("]
            (comma-sep (map #(fn [] (emit-constant %)) x))
            [")"])))

(defmethod emit-constant clojure.lang.IPersistentVector [x]
  (emit-meta-constant x
    (concat ["cljs.core.vec(["]
            (comma-sep (map #(fn [] (emit-constant %)) x))
            ["])"])))

(defmethod emit-constant clojure.lang.IPersistentMap [x]
  (emit-meta-constant x
    (concat ["cljs.core.hash_map("]
            (comma-sep (map #(fn [] (emit-constant %))
                            (apply concat x)))
            [")"])))

(defmethod emit-constant clojure.lang.PersistentHashSet [x]
  (emit-meta-constant x
    (concat ["cljs.core.set(["]
            (comma-sep (map #(fn [] (emit-constant %)) x))
            ["])"])))

(defn emit-block
  [context statements ret]
  (when statements
    (emits statements))
  (emit ret))

(defmacro emit-wrap [env & body]
  `(let [env# ~env]
     (when (= :return (:context env#))
       (when *finalizer* (emits *finalizer* "();"))
       (emits "return "))
     ~@body
     (when-not (= :expr (:context env#)) (emitln ""))))

(defmethod emit :no-op
  [m] (emitln "do end"))

(defmethod emit :var
  [{:keys [info env] :as arg}]
  (let [n (:name info)
        n (if (= (namespace n) "lua")
            (name n)
            n)]
  (emit-wrap env (emits (munge n)))))

(defmethod emit :meta
  [{:keys [expr meta env]}]
  (emit-wrap env
    (emits "cljs.core.with_meta(" expr "," meta ")")))

(def ^:private array-map-threshold 16)
(def ^:private obj-map-threshold 32)

;; TODO
(defmethod emit :map
  [{:keys [env simple-keys? keys vals]}]
  (emit-wrap env
    (cond
      (zero? (count keys))
      (emits "cljs.core.ObjMap.EMPTY")

      (and simple-keys? (<= (count keys) obj-map-threshold))
      (emits "cljs.core.ObjMap.fromObject(["
             (comma-sep keys) ; keys
             "],{"
             (comma-sep (map (fn [k v]
                               (with-out-str (emit k) (print ":") (emit v)))
                             keys vals)) ; js obj
             "})")

      (<= (count keys) array-map-threshold)
      (emits "cljs.core.PersistentArrayMap.fromArrays(["
             (comma-sep keys)
             "],["
             (comma-sep vals)
             "])")

      :else
      (emits "cljs.core.PersistentHashMap.fromArrays(["
             (comma-sep keys)
             "],["
             (comma-sep vals)
             "])"))))

;; TODO
(defmethod emit :vector
  [{:keys [items env]}]
  (emit-wrap env
    (if (empty? items)
      (emits "cljs.core.PersistentVector.EMPTY")
      (emits "cljs.core.PersistentVector.fromArray(["
             (comma-sep items) "], true)"))))


;; TODO
(defmethod emit :set
  [{:keys [items env]}]
  (emit-wrap env
    (emits "cljs.core.set(["
           (comma-sep items) "])")))

;; TODO
(defmethod emit :constant
  [{:keys [form env]}]
  (when-not (= :statement (:context env))
    (emit-wrap env (emit-constant form))))

(defn get-tag [e]
  (or (-> e :tag)
      (-> e :info :tag)))

(defn infer-tag [e]
  (if-let [tag (get-tag e)]
    tag
    (case (:op e)
      :let (infer-tag (:ret e))
      :if (let [then-tag (infer-tag (:then e))
                else-tag (infer-tag (:else e))]
            (when (= then-tag else-tag)
              then-tag))
      :constant (case (:form e)
                  true 'boolean
                  false 'boolean
                  nil)
      nil)))

(defn safe-test? [e]
  (let [tag (infer-tag e)]
    (or (#{'boolean 'seq} tag)
        (when (= (:op e) :constant)
          (let [form (:form e)]
            (not (or (and (string? form) (= form ""))
                     (and (number? form) (zero? form)))))))))

(defmethod emit :if
  [{:keys [test then else env unchecked]}]
  (let [checked (not (or unchecked (safe-test? test)))
        test-str (str (when checked "cljs.core.truth_") "(" (emit-str test) ")")]
    (if (in-expr? env)
      (emits "(function () if " test-str " then return " then " else return " else " end)()")
      (do
        (emitln "if " test-str " then")
        (emitln then " else ")
        (emitln else " end")))))

(defmethod emit :throw
  [{:keys [throw env]}]
  (do
    (when (in-expr? env) (emits "(function()"))
    (when *finalizer* (emits *finalizer* "();"))
    (emitln "error(" throw ")")
    (when (in-expr? env) (emits ")()"))))

(defn emit-comment
  "Emit a nicely formatted comment string."
  [doc jsdoc]

  (when *emit-comments*
    (let [docs (when doc [doc])
          docs (if jsdoc (concat docs jsdoc) docs)
          docs (remove nil? docs)]
      (letfn [(print-comment-lines [e] (doseq [next-line (string/split-lines e)]
                                         (emitln "  " (string/trim next-line))))]
        (when (seq docs)
          (emitln "--[")
          (doseq [e docs]
            (when e
              (print-comment-lines e)))
          (emitln "--]"))))))

(defmethod emit :def
  [{:keys [name init env doc export]}]
  (when init
    (let [mname (munge name)]
      (emit-comment doc (:jsdoc init))
      (when (in-expr? env) (emits "function () "))
      (emitln mname " = " init)
      (when (in-expr? env) (emits "; return " mname)))))

(defn emit-apply-to
  [{:keys [name params env]}]
  (let [arglist (gensym "arglist__")
        delegate-name (str (munge name) "__delegate")
        params (map munge params)]
    (emitln "(function (" arglist ")")
    (doseq [[i param] (map-indexed vector (butlast params))]
      (emits "local " param " = cljs.core.first(")
      (dotimes [_ i] (emits "cljs.core.next("))
      (emits arglist ")")
      (dotimes [_ i] (emits ")"))
      (emitln ""))
    (if (< 1 (count params))
      (do
        (emits "local " (last params) " = cljs.core.rest(")
        (dotimes [_ (- (count params) 2)] (emits "cljs.core.next("))
        (emits arglist)
        (dotimes [_ (- (count params) 2)] (emits ")"))
        (emitln ")")
        (emitln "return " delegate-name "(" (string/join ", " params) ")"))
      (do
        (emits "local " (last params) " = ")
        (emits "cljs.core.seq(" arglist ")")
        (emitln " ")
        (emitln "return " delegate-name "(" (string/join ", " params) ")")))
    (emits "end)")))

(defn emit-fn-method
  [{:keys [gthis name variadic params statements ret env recurs max-fixed-arity]}]
  (emit-wrap env
             (emitln "(function (" (comma-sep (map munge params)) ")")
             (when gthis
               (emitln "local " gthis " = " (munge (first params))))
             (when recurs (emitln "while true do"))
             (emit-block :return statements ret)
             (when recurs
               (emitln "break")
               (emitln "end"))
             (emits "end)")))

(defn emit-variadic-fn-method
  [{:keys [gthis name variadic params statements ret env recurs max-fixed-arity] :as f}]
  (emit-wrap
   env
   (let [name (or name (gensym))
         mname (munge name)
         params (map munge params)
         delegate-name (str mname "__delegate")]
     (emitln "(function () ")
     (emitln "local " delegate-name " = function (" (comma-sep params) ")")
     (when recurs (emitln "while true do"))
     (emit-block :return statements ret)
     (when recurs
       (emitln "break")
       (emitln "end"))
     (emitln "end")

     (emitln "local " mname " = {}")
     (emitln "local " mname "__func = function (_, " (comma-sep
                                             (if variadic
                                               (concat (butlast params) ["..."])
                                               params)) ")")
     (when gthis
       (emitln "local " gthis " = " (munge (first params))))
     (when variadic
       (emitln "local " (last params) " = cljs.core.array_seq({...},0);"))
     
     (emitln "return " delegate-name "(" (comma-sep params) ")")
     (emitln "end")
     
     (emitln mname ".cljs__lang__maxFixedArity = " max-fixed-arity)
     (emits mname ".cljs__lang__applyTo = ")
     (emit-apply-to (assoc f :name name))
     (emitln "")
     (emitln mname ".cljs__lang__arity__variadic = " delegate-name)
     (emitln "setmetatable(" mname ", {['__call'] = " mname "__func })")
     (emitln "return " mname)
     (emitln "end)()"))))

(defmethod emit :fn
  [{:keys [name env methods max-fixed-arity variadic recur-frames loop-lets]}]
  ;;fn statements get erased, serve no purpose and can pollute scope if named
  (when-not (= :statement (:context env))
    (let [loop-locals (->> (concat (mapcat :names (filter #(and % @(:flag %)) recur-frames))
                                   (mapcat :names loop-lets))
                           (map munge)
                           seq)]
      (when loop-locals
        (when (= :return (:context env))
          (emits "return "))
        (emitln "((function (" (comma-sep loop-locals) ")")
        (when-not (= :return (:context env))
          (emits "return ")))
      (if (= 1 (count methods))
        (if variadic
          (emit-variadic-fn-method (assoc (first methods) :name name))
          (emit-fn-method (assoc (first methods) :name name)))
        (let [has-name? (and name true)
              name (or name (gensym))
              mname (munge name)
              maxparams (map munge (apply max-key count (map :params methods)))
              mmap (into {}
                     (map (fn [method]
                            [(munge (symbol (str mname "__" (count (:params method)))))
                             method])
                          methods))
              ms (sort-by #(-> % second :params count) (seq mmap))]
          (when (= :return (:context env))
            (emits "return "))
          (emitln "(function() ")
          (emitln "local " mname " = {};")
          (doseq [[n meth] ms]
            (emits "local " n " = ")
            (if (:variadic meth)
              (emit-variadic-fn-method meth)
              (emit-fn-method meth))
            (emitln ""))
            (emitln "local " mname "__func = function(_, ...)")
          (when variadic
            (emitln "local " (last maxparams) " = var_args;"))

          (let [args-num (gensym "args_num")
                dispatch-cond (fn [n meth]
                                  (str args-num " == " (count (:params meth))))
                call-meth (fn [n meth] (emitln "return " n "(...)"))]
            
            (emitln "local " args-num " = select('#', ...)")
            (let [[n meth] (first ms)]              
              (emits "if " (dispatch-cond n meth) " then ")
              (call-meth n meth))
            
            (doseq [[n meth] (rest ms)]
              (if (:variadic meth)
                (emitln "else")
                (do (emits "elseif " (dispatch-cond n meth) " then ")))
              (call-meth n meth))
            (emitln "end")
            (emitln "throw('Invalid arity: ' + #" args-num ")")
            (emitln "end"))            
          
          (when variadic
            (emitln mname ".cljs__lang__maxFixedArity = " max-fixed-arity ";")
            (emitln mname ".cljs__lang__applyTo = " (some #(let [[n m] %] (when (:variadic m) n)) ms) ".cljs__lang__applyTo"))
          (when has-name?
            (doseq [[n meth] ms]
              (let [c (count (:params meth))]
                (if (:variadic meth)
                  (emitln mname ".cljs__lang__arity__variadic = " n ".cljs__lang__arity__variadic")
                  (emitln mname ".cljs__lang__arity__" c " = " n)))))
          
          (emitln "local __metatable = {['__call']= " mname "__func}")
          (emitln "setmetatable(" mname ", __metatable)")

          (emitln "return " mname)
          (emitln "end)()")))
      (when loop-locals
        (emitln "end)(" (comma-sep loop-locals) "))")))))

(defmethod emit :do
  [{:keys [statements ret env]}]
  (do
    (when (and statements (in-expr? env)) (emitln "(function ()"))
    (emit-block (:context env) statements ret)
    (when (and statements (in-expr? env)) (emits "end)()"))))

;; TODO
(defmethod emit :try*
  [{:keys [env try catch name finally]}]
  (let [context (:context env)
        subcontext (if (= :expr context) :return context)
        finally-sym (gensym "finally_func")
        success-sym (gensym "success")
        name (if name name (gensym "exception"))]
      
      (when (in-expr? env)  (emits "(function ()"))
      
      ;; Finalizer func
      (when finally
        (let [{:keys [statements ret]} finally]
          (assert (not= :constant (:op ret)) "finally block cannot contain constant")
          (emitln "local function " finally-sym "()")
          (emit-block subcontext statements ret)
          (emitln "end")))
      
      ;; Try block
      (emitln success-sym ", " name " = pcall(function()")
      (let [{:keys [statements ret]} try]
        (emit-block subcontext statements ret))
      (emitln "end)")
      
      (binding [*finalizer* (if finally finally-sym nil)]
        ;; Catch block
        (let [finalize-call (when finally (str *finalizer* "()"))]
          (emitln "if " success-sym " == false then")
          (if (and name catch)
            (let [{:keys [statements ret]} catch]
              (emit-block subcontext statements ret))
            (emitln finalize-call "; error(" name ")"))
          (emitln "else")
          (emitln finalize-call)
          (when (in-expr? env) (emitln "return " name))
          (emitln "end")))
      
      (when (= :expr context) (emits "end)()"))))

(defmethod emit :let
  [{:keys [bindings statements ret env loop]}]
  (let [context (:context env)]
    (binding [*loop-var* (when loop (gensym "loop_var"))]
      (when (= :expr context) (emitln "(function ()"))
      (doseq [{:keys [name init]} bindings]
        (emitln "local " (munge name) " = " init))
      (when loop
        (emitln "local " *loop-var* " = true")
        (emitln "while " *loop-var* " do")
        (emitln *loop-var* " = false"))
      (emit-block (if (= :expr context) :return context) statements ret)
      (when loop
        (emitln "end"))
                                        ;(emits "}")
      (when (= :expr context) (emits "end)()")))))

(defmethod emit :recur
  [{:keys [frame exprs env]}]
  (let [temps (vec (take (count exprs) (repeatedly gensym)))
        names (:names frame)]
    (emitln "do")
    (emitln *loop-var* " = true ")
    (dotimes [i (count exprs)]
      (emitln "local " (temps i) " = " (exprs i)))
    (dotimes [i (count exprs)]
      (emitln (munge (names i)) " = " (temps i)))
    (emitln "end")))

(defmethod emit :letfn
  [{:keys [bindings statements ret env]}]
  (let [context (:context env)]
    (when (= :expr context) (emitln "(function ()"))
    (doseq [{:keys [name init]} bindings]
      (emitln "local " (munge name) " = " init))
    (emit-block (if (= :expr context) :return context) statements ret)
    (when (= :expr context) (emitln "end)()"))))

(defn protocol-prefix [psym]
  (str (-> (str psym) (.replace \. \$) (.replace \/ \$)) "$"))

(defmethod emit :invoke
  [{:keys [f args env] :as expr}]
  (let [info (:info f)
        fn? (and ana/*cljs-static-fns*
                 (not (:dynamic info))
                 (:fn-var info))
        protocol (:protocol info)
        proto? (let [tag (infer-tag (first (:args expr)))]
                 (and protocol tag
                      (or ana/*cljs-static-fns*
                          (:protocol-inline env))
                      (or (= protocol tag)
                          (when-let [ps (:protocols (ana/resolve-existing-var (dissoc env :locals) tag))]
                            (ps protocol)))))
        opt-not? (and (= (:name info) 'cljs.core/not)
                      (= (infer-tag (first (:args expr))) 'boolean))
        ns (:ns info)
        lua? (= ns 'lua)
        keyword? (and (= (-> f :op) :constant)
                      (keyword? (-> f :form)))
        [f variadic-invoke]
        (if fn?
          (let [arity (count args)
                variadic? (:variadic info)
                mps (:method-params info)
                mfa (:max-fixed-arity info)]
            (cond
             ;; if only one method, no renaming needed
             (and (not variadic?)
                  (= (count mps) 1))
             [f nil]

             ;; direct dispatch to variadic case
             (and variadic? (> arity mfa))
             [(update-in f [:info :name]
                             (fn [name] (symbol (str (munge name) ".cljs__lang__arity__variadic"))))
              {:max-fixed-arity mfa}]

             ;; direct dispatch to specific arity case
             :else
             (let [arities (map count mps)]
               (if (some #{arity} arities)
                 [(update-in f [:info :name]
                             (fn [name] (symbol (str (munge name) ".cljs__lang__arity__" arity)))) nil]
                 [f nil]))))
          [f nil])]
    (emit-wrap env
      (cond
       opt-not?
       (emits "!(" (first args) ")")

       proto?
       (let [pimpl (str (protocol-prefix protocol)
                        (munge (name (:name info))) "__arity__" (count args))]
         (emits (first args) "." pimpl "(" (comma-sep args) ")"))

       keyword?
       (emits "(new cljs.core.Keyword(" f "))(" (comma-sep args) ")")
       
       variadic-invoke
       (let [mfa (:max-fixed-arity variadic-invoke)]
        (emits f "(" (comma-sep (take mfa args))
               (when-not (zero? mfa) ",")
               "cljs.core.array_seq([" (comma-sep (drop mfa args)) "], 0))"))
       
       (or fn? lua?)
       (emits f "(" (comma-sep args)  ")")
       
       :else
       (if (and ana/*cljs-static-fns* (= (:op f) :var))
         (let [fprop (str ".cljs__lang__arity__" (count args))]
           (emits "(" f fprop " ? " f fprop "(" (comma-sep args) ") : " f "(" (comma-sep args) "))"))
         (emits f "(" (comma-sep args) ")"))))))

(defmethod emit :new
  [{:keys [ctor args env]}]
  (emit-wrap env
             (emits "(" ctor ".new("
                    (comma-sep args)
                    "))")))

(defmethod emit :set!
  [{:keys [target val env]}]
  (emit-wrap env (emits target " = " val)))

(defmethod emit :ns
  [{:keys [name requires uses requires-macros env]}]
  (emitln "builtins.create_namespace('" (munge name) "')")
  (comment (when-not (= name 'cljs.core)
             (emitln "require 'cljs.core'")))
  (doseq [lib (into (vals requires) (distinct (vals uses)))]
    (emitln "require '" (munge lib) "'")))

(defmethod emit :deftype*
  [{:keys [t fields pmasks]}]
  (let [fields (map munge fields)]
    (emitln "")
    (emitln "--[[")
    (emitln "-- @constructor")
    (emitln "--]]")
    (emitln (munge t) " = {}")
    (emitln (munge t) ".proto_methods = {}")
    (emitln (munge t) ".new = (function (" (comma-sep fields) ")")
    (emitln "local instance = {}")
    (emitln "instance.proto_methods = " (munge t) ".proto_methods")
    (doseq [fld fields]
      (emitln "instance." fld " = " fld))
    (comment (doseq [[pno pmask] pmasks]
               (emitln "instance.cljs__lang__protocol_mask__partition" pno "__ = " pmask)))
    (emitln "return instance")
    (emitln "end)")))

(defmethod emit :defrecord*
  [{:keys [t fields pmasks]}]
  (let [fields (concat (map munge fields) '[__meta __extmap])]
    (emitln (munge t) " = (function (" (comma-sep fields) ")")
    (doseq [fld fields]
      (emitln "this." fld " = " fld ";"))
    (doseq [[pno pmask] pmasks]
      (emitln "this.cljs__lang__protocol_mask__partition" pno "__ = " pmask))
    (emitln "end)")))

(defmethod emit :dot
  [{:keys [target field method args env]}]
  (emit-wrap env
             (if field
               (emits target "." (munge field #{}))
               (emits target "." (munge method #{}) "("
                      (comma-sep args)
                      ")"))))

(defmethod emit :js
  [{:keys [env code segs args]}]
  (emit-wrap env
             (if code
               (emits code)
               (emits (interleave (concat segs (repeat nil))
                                  (concat args [nil]))))))

(defmacro lua [form]
  `(ana/with-core-macros "/cljs/lua/core"
     (emit (ana/analyze {:ns (@ana/namespaces 'cljs.user) :context :statement :locals {}} '~form))))

(def lua-interp "lua")
(def ^:dynamic *repl-verbose* true)
(def ^:dynamic *repl-exec* true)
(def special-fns
  {'switch-verbose (fn [] (set! *repl-verbose* (not *repl-verbose*)))
   'switch-exec (fn [] (set! *repl-exec* (not *repl-exec*)))})
(def special-fns-set (set (keys special-fns)))

(defn -main []
  (ana/with-core-macros "/cljs/lua/core"
    (println "Cljs/Lua repl")
    (binding [ana/*cljs-ns* 'cljs.user
              *repl-verbose* true
              *repl-exec* true]
      (let [new-env (fn [] {:ns (@ana/namespaces ana/*cljs-ns*) :context :return :locals {}})
            pb (ProcessBuilder. [lua-interp "cljs/exec_server.lua"])
            lua-process (.start pb)
            rdr (io/reader (.getInputStream lua-process))
            eval-form (fn [env form]
                        (let [lua-code (with-out-str (emit (ana/analyze env form)))]
                          (when *repl-exec*
                            (binding [*out* (PrintWriter. (.getOutputStream lua-process))]
                              (println (json/json-str {:action :exec :body lua-code}))))
                          {:lua-code lua-code :response (when *repl-exec* (json/read-json rdr))}))]
        (.readLine rdr)
        (eval-form (new-env) '(ns cljs.user))        
        (while true
            (.print System/out (str ana/*cljs-ns* " > "))
            (.flush (System/out))
            (let [env (new-env)
                  form (read)
                  special-fn? (contains? special-fns-set (first form))
                  res (when (not special-fn?) (eval-form env form))]
              (if special-fn?
                (println (apply (special-fns (first form)) (rest form)))
                (do
                  (when *repl-verbose*
                    (println "---- LUA CODE ----")
                    (println (:lua-code res)))
                  (let [resp (:response res)]
                    (when resp
                      (if (= (:status resp) "OK")
                        (println (:body resp))
                        (println "ERROR"))))))))))))