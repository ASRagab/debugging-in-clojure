(ns app.auto-mapper
  "Core utilities for converting CSV data-source specs into valid JSON mappings for Hailstorm."
  (:require [clojure.java.io :as io]
            [clojure.data.csv :as csv]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [cheshire.core :as json]
            [taoensso.timbre :as log])
  (:import (org.apache.commons.cli Options Option CommandLineParser
                                   HelpFormatter CommandLine ParseException
                                   DefaultParser))
  (:gen-class))


(def sample-document
  {"Analyzed"         "Y",
   "Nested"           "Y",
   "Preview"          "Y",
   "Queryable"        "Y",
   "Verbatim"         "Y",

   "Description"      "Complaint Text",
   "Field Type"       "STRING",
   "Friendly Name"    "Complaint Text",
   "GM Column Alias"  "cdescr",
   "Notes"            "Complaint text",
   "Original Name"    "CMPLN_DESC",
   "Pattern"          "HIVE",

   "Sample Field Values" "REGARDLESS OF HOW FUEL NOZZLE IS PLACED, FUEL TANK OVER FILLS APPROX 1/8TH-1/3TH GALLON OF FUEL.  I FEAR THIS COULD CAUSE A FIRE IN THE EVENT FUEL SPLASHED ONTO BRAKE ROTOR OR OTHER HOT COMPONENTS, NOT TO MENTION THE ENVIRONMENTAL AND FINANCIAL IMPACT OF THIS ISSUE DIRECTLY RELATED TO POOR DESIGN.  *TR",
   "Section" "Activity",
   "Signafire Alias" "activity.complaintText",
   "Signafire Source Field Name" "cdescr as `complaint.cdescr`,",
   "Source Table" "SIGNAFIRE.VOQ_FINAL",
   "Subdocument" "activity",
   "Transforms" ""})


(def sample-output
  {"manufacturedByGM"
   {"type" "boolean",
    "index" "not_analyzed",
    "_meta"
    {"source" "doc.voq.gm_manuf_flg",
     "title" "Manufactured by GM",
     "description" "Manufactured by GM",
     "transform"
     {"[Yy]" true,
      "[Nn]" false}}}})


(defn update-template
  "Takes template and inserts properties of doc into it"
  [template ks doc-type props]
  (update-in template ks (fn [base]
                           (let [doc-props (get-in props [doc-type :properties]) ;; pulls out doc props and merges them at higher level in mapping
                                 updated-props (dissoc props doc-type)]
                             (merge base updated-props doc-props)))))

(defn handle-template
  "Reads in Template file, string replaces placeholder and updates with doc properties"
  [doc-type file ks props]
  (-> (slurp file)
      (clojure.string/replace #"%%doc%%" doc-type)
      (json/parse-string)
      (update-template ks doc-type props)))


(defn csv-data->maps
  "Convert header + body rows into a sequence of maps"
  [csv-data]
  (map zipmap (repeat (first csv-data)) (rest csv-data)))


(defn parse-specification
  "Grabs a datasource specification from CSV
  and converts it into a sequence of maps."
  [file]
  (with-open [rdr (io/reader file)]
    (let [raw-csv (csv/read-csv rdr)]
      (doall (csv-data->maps raw-csv)))))


(defn infer-source
  "Infer a parent key's [:_meta :source] value from data."
  [source-fields]
  (log/debug "Source fields:" source-fields)
  (let [source      (first source-fields)
        prefix-end  (str/index-of source ".")]
    (subs source 0 prefix-end)))


(defn specified-type->storage-type
  [given-type]
  (log/trace "Given type: " given-type)
  (case given-type
    "BOOL"      "boolean"
    "DATE"      "date"
    "DATETIME"  "datetime"
    "ENUM"      "string"
    "ID"        "string"
    "INT"       "integer"
    "STRING"    "string"
    "FLOAT"     "float"
    "SHORT"     "integer"
    "LONG"      "long"))


(defn assoc-transform-snippet
  "Utility method to assoc in transforms into output field"
  [output-field snippet]
  (assoc-in output-field [:_meta :transform] snippet))


(defn transform-type->transform
  "Adds transforms based on transform type"
  [initial transform-type]
  (case (clojure.string/lower-case  transform-type)
    "ynbool" (assoc-transform-snippet initial {"[Yy]" true "[Nn]" false})
    "int" (assoc-transform-snippet initial {:type "number"})
    initial))


(defn apply-storage-type
  "Handles storage type formatting when applicable"
  [initial]
  (case (:type initial)
    "date" (assoc initial :format "strict_date_optional_time")  ;; JUST AS A DEFAULT
    "datetime" (assoc initial :format "strict_date_time")  ;; JUST AS A DEFAULT
    initial))


(defn generate-output-field
  "Adds additional properties, like transform and storage format to mapping field"
  [initial transform-type]
  (-> (apply-storage-type initial)
      (transform-type->transform transform-type)))


(defn row-to-field
  "Converts a map representing a data source field
  into the map format expected in the resulting JSON"
  [row]
  (let [description           (get row "Description")
        field-type            (get row "Field Type")
        friendly-name         (get row "Friendly Name")

        analyzed?             (= "Y" (get row "Analyzed"))
        nested?               (= "Y" (get row "Nested"))
        preview?              (= "Y" (get row "Preview"))
        queryable?            (= "Y" (get row "Queryable"))
        verbatim?             (= "Y" (get row "Verbatim"))

        gm-alias              (get row "GM Column Alias")
        notes                 (get row "Notes")
        original-name         (get row "Original Name")
        pattern               (get row "Pattern")
        sample                (get row "Sample Field Values")
        section               (get row "Activity")

        sf-alias              (get row "Signafire Alias")
        sf-source-field-name  (get row "Signafire Source Field Name")
        source-table          (get row "Source Table")
        transforms            (get row "Transforms")

        storage-type          (specified-type->storage-type field-type)

        analyze-status        (cond (and queryable? verbatim?) "analyzed"
                                    (and queryable? (not verbatim?)) "not_analyzed"
                                    (not queryable?) "no"
                                    analyzed? "analyzed"
                                    :default "not_analyzed")

        initial-field          {:type  storage-type
                                :index analyze-status
                                :_meta {:source gm-alias :title friendly-name :description description}
                                :sf/nested? nested?
                                :sf/source-field-name sf-source-field-name}

        output-field          (generate-output-field initial-field transforms)

        sf-alias'             (if-let [i (str/index-of sf-alias ".")]
                                (subs sf-alias (inc i))
                                sf-alias)]

    ;; FIXME: what if there are two or more comma-delimited signafire aliases?

    [sf-alias' output-field]))


(defn remove-private-keys
  [m]
  (into {} (for [[k v] m] [k (dissoc v :sf/nested? :sf/source-field-name)])))


(defn rewrite-sources
  "Rewrites non-path source strings to contain the root and sub-document."
  [top-level root m]
  (log/trace "Rewriting " m)
  (into {} (for [[k v] m] [k (update-in v [:_meta :source] (fn [old-source]
                                                             (log/trace "Old source: " old-source)
                                                             (str top-level "." root "." old-source)))])))


(defn rewrite-nested-fields
  "Removes signafire private keys and updates nested fields
  to have the correct output format."
  [top-level-key second-top-level-key proto-mapping]
  (into {}
        (for [[k vs] proto-mapping]
          (let [properties            (:properties vs)
                prop-vals             (vals properties)
                source-fields         (map :sf/source-field-name prop-vals)
                props-without-sf-keys (remove-private-keys properties)]
            (if (some :sf/nested? prop-vals)
              [k (assoc  vs :type "nested"
                         :properties props-without-sf-keys
                         :_meta {:source (str top-level-key "." (infer-source source-fields))})]
              [k (assoc vs :properties (rewrite-sources top-level-key second-top-level-key props-without-sf-keys))])))))


(defn reorder-fields
  "Chooses a single field `root` as the parent of all others."
  [root doc]
  (let [base (get doc root)]
    (into base (dissoc doc root))))


(defn encode
  "Pretty print json"
  [m]
  (json/generate-string m {:pretty true}))

;; This is always going to be "doc", but we leave it here
;; to make it easy to change it later
(def default-top-level-key "doc")
(defn template-path [source-doc-type] [source-doc-type "mappings" source-doc-type "properties"])

(defn process-specification
  [top-level-key source-doc-type rows]
  (->> rows
       (group-by #(get % "Subdocument"))
       (map (fn [[k vs]] [k {:properties (into {} (map row-to-field vs))}]))
       (rewrite-nested-fields top-level-key source-doc-type)
       (handle-template source-doc-type "./resources/template.json" (template-path source-doc-type)) ;;TODO Parameterize template file and keys
       (encode)))


(def application-name "auto-mapper")


(defn quote-str
  "Utility to quote input strings for printing"
  [s]
  (str "\"" s "\""))


(defn -main
  [& args]
  (log/set-level! :info)
  ;; TODO: parameterize this
  (let [options   (Options.)
        input     (Option. "i" "input" true "Input csv data source specification.")
        output    (Option. "o" "output" true "Output file to write the generated mapping to.")
        source    (Option. "s" "source" true "Name of the data source.")
        parser    (DefaultParser.)
        formatter (HelpFormatter.)]

    (.setRequired input true)
    (.setRequired output true)
    (.setRequired source true)

    (.addOption options input)
    (.addOption options output)
    (.addOption options source)

    (try
      (let [cmd           (.parse parser options (into-array String args))
            input-name    (.getOptionValue cmd "input")
            output-name   (.getOptionValue cmd "output")
            source-name   (.getOptionValue cmd "source")

            input-file    (io/file input-name)
            output-file   (io/file output-name)]

        (when (or (str/blank? input-name) (not (.exists input-file)))
          (log/error "Input file" (quote-str input-name) "does not exist")
          (System/exit 1))

        (when (str/blank? output-name)
          (log/error "Output file must have a valid name! Given:" (quote-str output-name))
          (System/exit 1))

        (when (.exists output-file)
          (log/warn "Overwriting existing file" (quote-str output-file)))


        (when (str/blank? source-name)
          (log/error "Source name `" (quote-str source-name) "` must be non-blank!")
          (System/exit 1))

        (with-open [writer (io/writer output-file)]
          (let [rows (parse-specification input-file)
                output-json (process-specification default-top-level-key source-name rows)]
            (.write writer ^String output-json))))

      (catch ParseException ex
        (println (.getMessage ex))
        (.printHelp formatter application-name options)))))
