(ns cljs.core)

(defprotocol Object (toString [x]))

(defprotocol IStringBuffer (append [x str]))

(deftype StringBuffer []
  Object
  (toString [x] (table/concat x))
  IStringBuffer
  (append [x str] (table/insert x str) x))

(defn pr-str [] "NIY")

(defprotocol INode
  (inode-assoc [inode shift hash key val added-leaf?])
  (inode-without [inode shift hash key])
  (inode-lookup [inode shift hash key not-found])
  (inode-find [inode shift hash key not-found])
  (inode-seq [inode])
  (ensure-editable [inode e])
  (inode-assoc! [inode edit shift hash key val added-leaf?])
  (inode-without! [inode edit shift hash key removed-leaf?])
  (kv-reduce [inode f init]))

(defprotocol IHashCollisionNode
  (ensure-editable-array [inode e count array]))

(defprotocol IBitmapIndexedNode
  (edit-and-remove-pair [inode e bit i]))

(defprotocol ITransientHashMap
  (tconj! [tcoll o])
  (tassoc! [tcoll k v])  
  (twithout! [tcoll k])
  (tpersistent! [tcoll]))
  
;; default type
(deftype default [])
(defn default-proto-table [] (.-proto-methods cljs.core.default))
(builtins/init-meta-tables)