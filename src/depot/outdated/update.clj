(ns depot.outdated.update
  (:require [clojure.tools.deps.alpha.reader :as reader]
            [depot.outdated :as depot]
            [depot.zip :as dzip]
            [rewrite-clj.zip :as rzip]
            [clojure.zip :as zip]))

(defmacro with-print-namespace-maps [bool & body]
  (if (find-var 'clojure.core/*print-namespace-maps*)
    `(binding [*print-namespace-maps* ~bool]
       ~@body)
    ;; pre Clojure 1.9
    `(do ~@body)))

(defn- new-versions
  [deps consider-types repos]
  (into {}
        (pmap (fn [[artifact coords]]
                (let [[old-version version-key]
                      (or (some-> coords :mvn/version (vector :mvn/version))
                          (some-> coords :sha (vector :sha)))
                      new-version (-> (depot/current-latest-map artifact
                                                                coords
                                                                {:consider-types consider-types
                                                                 :deps-map repos})
                                      (get "Latest"))]
                  (when (and old-version
                             ;; ignore these Maven 2 legacy identifiers
                             (not (#{"RELEASE" "LATEST"} old-version))
                             new-version)
                    [artifact {:version-key version-key
                               :old-version old-version
                               :new-version new-version}])))
              deps)))

(defn- apply-new-version
  [new-versions [artifact coords]]
  (let [{version-key :version-key
         new-version :new-version
         old-version :old-version :as v} (get new-versions artifact)]
    (if (and (not (:depot/ignore (meta artifact))) v)
      (do
        (with-print-namespace-maps false
          (println " " artifact (pr-str {version-key old-version}) "->" (pr-str {version-key new-version})))
        (assoc coords version-key new-version))
      coords)))

(defn update-deps
  "Update all deps in a `:deps` or `:extra-deps` or `:override-deps` map, at the
  top level and in aliases.

  `loc` points at the top level map."
  [loc consider-types repos]
  (let [new-versions (new-versions (dzip/lib-seq loc) consider-types repos)]
    (dzip/transform-coords loc (partial apply-new-version new-versions))))

(defn update-deps-edn!
  "Destructively update a `deps.edn` file.

  Read a `deps.edn` file, update all dependencies in it to their latest version,
  unless marked with `^:depot/ignore` metadata, then overwrite the file with the
  updated version. Preserves whitespace and comments.

  This will consider user and system-wide `deps.edn` files for locating Maven
  repositories, but only considers the given file when determining current
  versions.

  `consider-types` is a set, one of [[depot.outdated/version-types]]. "
  [file consider-types]
  (println "Updating:" file)
  (let [deps (-> (reader/clojure-env)
                 :config-files
                 reader/read-deps)

        repos    (select-keys deps [:mvn/repos :mvn/local-repo])
        loc      (rzip/of-file file)
        old-deps (slurp file)
        loc'     (update-deps loc consider-types repos)
        new-deps (rzip/root-string loc')]
    (when (and loc' new-deps) ;; defensive check to prevent writing an empty deps.edn
      (if (= old-deps new-deps)
        (println "  All up to date!")
        (try
          (spit file new-deps)
          (catch java.io.FileNotFoundException e
            (println "  [ERROR] Permission denied: " file)))))))
