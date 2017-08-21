(ns cljam.io.util.bgzf
  (:refer-clojure :exclude [compare read])
  (:require [cljam.io.util.lsb :as lsb]
            [com.climate.claypoole :as cp])
  (:import [java.io RandomAccessFile Closeable InputStream]
           [bgzf4j BGZFInputStream]
           [java.util.zip Inflater]))

(def ^:private ^:const shift-amount 16)

(def ^:private ^:const address-mask 0xFFFFFFFFFFFF)

(def ^:private ^:const offset-mask 0xFFFF)

(defn compare
  "Negative if fp1 is earlier in file than fp2, positive if it is later, 0 if equal."
  [^long fp1 ^long fp2]
  (cond
   (= fp1 fp2)                 0
   ;; When treating as unsigned, negative number is > positive.
   (and (< fp1 0) (>= fp2 0))  1
   (and (>= fp1 0) (< fp2 0)) -1
   ;; Either both negative or both non-negative, so regular comparison works.
   (< fp1 fp2)                -1
   :else                       1))

(defn- make-file-pointer
  [^long block-address ^long block-offset]
  (bit-or (bit-shift-left block-address shift-amount) block-offset))

(defn get-block-address
  "File offset of start of BGZF block for this file pointer."
  [^long fp]
  (bit-and (bit-shift-right fp shift-amount) address-mask))

(defn get-block-offset
  "Offset into uncompressed block for this virtual file pointer."
  [^long fp]
  (bit-and fp offset-mask))

(defn same-or-adjacent-blocks?
  "Returns true if fp2 points to somewhere in the same BGZF block, or the one
  immediately following fp1's BGZF block."
  [^long fp1 ^long fp2]
  (let [block1 (long (get-block-address fp1))
        block2 (long (get-block-address fp2))]
    (or (= block1 block2) (= (inc block1) block2))))

(def ^:private ^:const header-length 18)
(def ^:private ^:const footer-length 8)

(defmacro little-endian [& xs]
  (->> xs
       (map-indexed (fn [i x] (list `bit-shift-left `(bit-and 0xFF ~x) (* 8 i))))
       (apply list `bit-or)))

(defn ^"[B" read-a-block [^RandomAccessFile rdr]
  (let [header-buf (byte-array header-length)
        _ (.readFully rdr header-buf)
        block-length (inc
                      (little-endian
                       (aget header-buf (- header-length 2))
                       (aget header-buf (- header-length 1))))
        compressed (byte-array block-length)]
    (System/arraycopy header-buf 0 compressed 0 header-length)
    (.readFully rdr compressed header-length (- block-length header-length))
    compressed))

(deftype BGZFUncompressedBlock [^bytes bytes ^int block-length])
(defn ^BGZFUncompressedBlock  decode-a-block [^bytes compressed]
  (let [cmp-len (alength compressed)
        unc-len (little-endian
                 (aget compressed (- cmp-len 4))
                 (aget compressed (- cmp-len 3))
                 (aget compressed (- cmp-len 2))
                 (aget compressed (- cmp-len 1)))
        uncompressed (byte-array unc-len)
        inflater (Inflater. true)]
    (.reset inflater)
    (.setInput inflater compressed header-length (- cmp-len header-length footer-length))
    (.inflate inflater uncompressed 0 unc-len)
    (.end inflater)
    (BGZFUncompressedBlock. uncompressed cmp-len)))

(defn block-seq [^RandomAccessFile rdr]
  (when-not (zero? (- (.length rdr) (.getFilePointer rdr)))
    (cons (read-a-block rdr)
          (lazy-seq (block-seq rdr)))))

(defn read-content [pool rdr]
  (cp/pmap pool decode-a-block (block-seq rdr)))

(defprotocol IBGZFSeekable
  (get-file-pointer [this])
  (seek [this pointer]))

(defrecord ParallelBGZFInputStream [raf addr offset buf-seq p]
  Closeable
  (close [this]
    (.close ^RandomAccessFile raf)
    (cp/shutdown! p))
  IBGZFSeekable
  (get-file-pointer [this]
    (let [blk ^BGZFUncompressedBlock (first @buf-seq)]
      (if (= @offset (alength ^bytes (.bytes blk)))
        (make-file-pointer (+ @addr (.block-length blk)) 0)
        (make-file-pointer @addr @offset))))
  (seek [this pointer]
    (let [new-addr (get-block-address pointer)
          new-offset (get-block-offset pointer)]
      (.seek ^RandomAccessFile raf new-addr)
      (reset! buf-seq (read-content p raf))
      (reset! addr new-addr)
      (reset! offset new-offset)))
  InputStream
  (available [this]
    (when-let [blk ^bytes (.bytes ^BGZFUncompressedBlock (first @buf-seq))]
      (when (= @offset (alength blk))
        (swap! buf-seq next)
        (reset! offset 0)))
    (if-let [blk ^bytes (.bytes ^BGZFUncompressedBlock (first @buf-seq))]
      (- (alength blk) @offset)
      0))
  (markSupported [this] false)
  (skip [this n]
    (let [ba (byte-array n)]
      (read this ba)))
  (read [this]
    (let [ba (byte-array 1)]
      (read ba)
      (bit-and (aget ba 0) 0xFF)))
  (read [this buf]
    (read this buf 0 (alength ^bytes buf)))
  (read [this buf off len]
    (let [first-buf ^bytes (.bytes ^BGZFUncompressedBlock (first @buf-seq))
          buf-len (alength first-buf)
          remain (- buf-len @offset)]
      (if (< len remain)
        (do
          (System/arraycopy first-buf @offset buf off len)
          (swap! offset + len)
          buf)
        (let [new-offset (- len remain)]
          (System/arraycopy first-buf @offset buf off remain)
          (swap! buf-seq next)
          (reset! offset new-offset)
          (when (pos? new-offset)
            (System/arraycopy (.bytes ^BGZFUncompressedBlock (first @buf-seq)) 0 buf remain new-offset))
          buf)))))

(defn bgzf-reader
  ([f]
   (bgzf-reader f {}))
  ([f {:keys [n-threads] :or {n-threads (cp/ncpus)}}]
   (let [raf (RandomAccessFile. ^String f "r")
         pool (cp/threadpool n-threads)]
     (ParallelBGZFReader. raf (atom 0) (atom 0) (atom (read-content pool raf)) pool))))
