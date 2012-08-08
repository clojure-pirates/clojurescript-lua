;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns cljs.lua.repl
  (:require [clojure.java.io :as io]
            [cljs.lua.compiler :as comp]
            [cljs.analyzer :as ana]
            [cljs.cljsloader :as cloader]
            [clojure.data.json :as json]
            [cljs.lua.common :as com]
            [cljs.lua.config :as conf])
  (:import  [java.io PrintWriter File FileInputStream FileOutputStream]))

(def ^:dynamic *lua-interp* nil)
(def ^:dynamic *repl-verbose* true)
(def ^:dynamic *repl-exec* true)
(def ^:dynamic *repl-show-result* true)
(def ^:dynamic *error-fatal?* false)
(def next-core-form (atom 0))

(def nenv (partial com/new-env :return))

(defn eval-core-forms [eval-fn n]
  (let [current-ns ana/*cljs-ns*]
    (binding [*repl-verbose* false] (eval-fn (nenv) '(ns cljs.core)))
    (doseq [form (if (= n -1)
                   com/core-forms-seq
                   (take n (drop @next-core-form com/core-forms-seq)))]
      (eval-fn (nenv) form))
    (binding [*repl-verbose* false] (eval-fn (nenv) (list 'ns current-ns)))
    (swap! next-core-form + n)))

(def special-fns
  {'switch-verbose (fn [_] (set! *repl-verbose* (not *repl-verbose*)))
   'switch-exec (fn [_] (set! *repl-exec* (not *repl-exec*)))
   'eval-core-forms eval-core-forms})

(def special-fns-set (set (keys special-fns)))

(defn create-named-pipe [pfx]
  (let [pipe-path (-> (str "cljs_lua_" pfx "_")
                      (File/createTempFile ".fifo")
                      .getCanonicalPath)]
    (.waitFor (.exec (Runtime/getRuntime) (str "rm " pipe-path)))
    (.waitFor (.exec (Runtime/getRuntime) (str "mkfifo " pipe-path)))
    (File. pipe-path)))

(defn -main [& args]
  (println "Cljs/Lua repl")    
  (binding [ana/*cljs-ns* 'cljs.user
            ana/*cljs-static-fns* true
            *repl-verbose* false
            *repl-exec* true
            *lua-interp* (conf/get :repl :lua-runtime)]
    (let [;; Lua subprocess
          pb (ProcessBuilder. [*lua-interp* "cljs/exec_server.lua"])
          lua-process (.start pb)
          
          ;; Read lua stdout
          rdr (io/reader (.getInputStream lua-process))

          ;; Named pipes to communicate with lua subproc
          pipe-in (create-named-pipe "ltj") 
          pipe-out (create-named-pipe "jtl")
          pipe-rdr (future (io/reader pipe-in))
          pipe-wr (future (io/writer pipe-out))

          ;; Function to analyze a form, emit lua code,
          ;; pass it to the lua subproc, and get back the result
          eval-form (fn [env form]
                      (let [lua-code (with-out-str (comp/emit (ana/analyze env form)))]
                        (when *repl-verbose*
                          (println "---- LUA CODE ----")
                          (println lua-code))
                        (when *repl-exec*
                          (binding [*out* @pipe-wr]
                            (println (json/json-str {:action :exec :body lua-code})))
                          (let [resp (json/read-json (.readLine @pipe-rdr))]
                            (if (= (:status resp) "OK")
                              (when *repl-show-result* (println (:body resp)))
                              (do
                                (println "ERROR : " (:body resp))
                                (when *error-fatal?*
                                  (println lua-code)
                                  )))))))]

      ;; Redirect everything from subprocess stdout to own stdout
      (.start (Thread. (fn [] (while true (let [l (.readLine rdr)] (when l (println l)))))))

      (try (do
             (.exitValue lua-process)
             (println "Lua subprocess has exited prematurely, verify you have lua installed, and required libraries : lua-json and lua-bitops")
             (System/exit 0))
           (catch Exception e))

      ;; Send it the two pipes names
      (binding [*out* (PrintWriter. (.getOutputStream lua-process))]
        (println (.getCanonicalPath pipe-in))
        (println (.getCanonicalPath pipe-out)))

      ;; Eval core.cljs forms
      (binding [*repl-verbose* false
                *repl-show-result* false
                *error-fatal?* true]
        (eval-core-forms eval-form -1))
      
      ;; Eval common ns forms
      (eval-form (nenv) '(ns cljs.user))
      
      ;; Repl loop
      (while true
        (.print System/out (str ana/*cljs-ns* " > "))
        (.flush (System/out))
        (let [env (nenv)
              form (read)
              special-fn? (and (seq? form) (contains? special-fns-set (first form)))]
          (if special-fn?
            (println (apply (special-fns (first form)) eval-form (rest form)))
            (try (eval-form env form)
                 (catch Exception e
                   (.printStackTrace e))
                 (catch AssertionError a
                   (.printStackTrace a)))))))))