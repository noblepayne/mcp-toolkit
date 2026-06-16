(ns ^:no-doc mcp-toolkit.impl.common
  (:require [clojure.string :as str]))

(defn user-callback [callback-key]
  (fn [context]
    (when-some [callback (-> context :session deref (get callback-key))]
      (callback context))))

(defn munge-name
  "Convert a namespaced keyword to an MCP protocol string.
   :art19/list-episodes → \"art19/list-episodes\"

   Uses the keyword's own namespace/name split (via Clojure's
   canonical representation) — no lossy encoding needed."
  [k]
  (if-let [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(defn unmunge-name
  "Convert an MCP protocol string to a namespaced keyword.
   \"art19/list-episodes\" → :art19/list-episodes

   Strings without '/' become non-namespaced keywords.
   Uses Clojure's own keyword parsing — no lossy decoding needed."
  [s]
  (if (str/includes? s "/")
    (let [pos (str/index-of s "/")]
      (keyword (subs s 0 pos) (subs s (inc pos))))
    (keyword s)))
