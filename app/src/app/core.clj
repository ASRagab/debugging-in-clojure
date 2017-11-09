(ns app.core
  (:require [clojure.pprint :as pretty]
            [cheshire.parse :as p]
            [cheshire.core :as c]))

(defn do-stuff [x y z]
  (let [file (slurp "./README.md")
        hello (take x file)]
    #dbg ^{:break/when (some? file)}
    (+ y z (count hello))))

(do-stuff 10 1 3)

(defn do-stuff2 [x y z]
  (do-stuff x y z))

(do-stuff2 10 2 5)

(defn foo
  "I don't do a whole lot."
  [x]
  (println #spy/d x "Hello, World!"))

(foo 10)

(defn call-future [p] #spy/t ^{:time true} (future (Thread/sleep 1000) (+ 4 (:value {:value p :something "else"}))))

(call-future 100)

