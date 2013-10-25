(ns cljam.bam
  (:refer-clojure :exclude [slurp spit])
  (:require [cljam.io :as io]
            (cljam.bam [reader :as bam-reader]
                       [writer :as bam-writer])))

(defn reader
  [f]
  (bam-reader/reader f))

(defn writer
  [f]
  (bam-writer/writer f))

(defn slurp
  "Opens a reader on bam-file and reads all its headers and alignments,
  returning a map about sam records."
  [f & options]
  (let [{:keys [chr start end] :or {chr nil
                                    start 0
                                    end -1}} options]
    (with-open [r (bam-reader/reader f)]
      (let [h (io/read-header r)]
        {:header h
         :alignments (if (nil? chr)
                       nil
                       (vec (io/read-alignments r
                                                {:chr chr :start start :end end})))}))))

(defn spit
  "Opposite of slurp-bam. Opens bam-file with writer, writes sam headers and
  alignments, then closes the bam-file."
  [f sam]
  (with-open [w (bam-writer/writer f)]
    (io/write-header w (:header sam))
    (io/write-refs w (:header sam))
    (io/write-alignments w (:alignments sam) (:header sam))))