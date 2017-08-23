(ns cljam.io.util.pbgzf
  (:require [com.climate.claypoole :as cp]
            [com.climate.claypoole.lazy :as lazy]
            [cljam.io.util.bgzf :as bgzf])
  (:import [java.io RandomAccessFile InputStream Closeable]
           [java.util.zip Inflater]))

(def ^:private ^:const header-length 18)
(def ^:private ^:const footer-length 8)

(defmacro little-endian [& xs]
  (->> xs
       (map-indexed (fn [i x] (list `bit-shift-left `(bit-and 0xFF ~x) (* 8 i))))
       (apply list `bit-or)))

(defn- ^"[B" read-a-block [^RandomAccessFile rdr]
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
(defn- ^BGZFUncompressedBlock  decode-a-block [^bytes compressed]
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

(defn- block-seq [^RandomAccessFile rdr]
  (when-not (zero? (- (.length rdr) (.getFilePointer rdr)))
    (cons (read-a-block rdr)
          (lazy-seq (block-seq rdr)))))

(defn- read-content [pool rdr]
  (lazy/pmap-buffer pool 10000 decode-a-block (block-seq rdr)))

(gen-class
 :name cljam.io.util.pbgzf.ParallelBGZFInputStream
 :extends java.io.InputStream
 :state "state"
 :init "init"
 :constructors {[String long] []}
 :prefix pbgzfis-
 :main false)

(defn pbgzfis-init [f n-threads]
  (let [raf (RandomAccessFile. ^String f "r")
        pool (cp/threadpool n-threads)]
    [[] (atom {:raf raf
               :addr 0
               :offset 0
               :buf-seq nil
               :pool pool})]))

(defn pbgzfis-close [^cljam.io.util.pbgzf.ParallelBGZFInputStream this]
  (.close ^RandomAccessFile (:raf @(.state this)))
  (cp/shutdown! (:pool @(.state this))))

(defn pbgzfis-read-byte<>-int-int [^cljam.io.util.pbgzf.ParallelBGZFInputStream this ^bytes b off len]
  (when (nil? (first (:buf-seq @(.state this))))
    (swap! (.state this) assoc :buf-seq (read-content (:pool @(.state this)) (:raf @(.state this)))))
  (loop [tgt-offset (long off)
         read-len (long len)]
    (let [block ^BGZFUncompressedBlock (first (:buf-seq @(.state this)))
          block-bytes ^bytes (.bytes block)
          block-len (alength block-bytes)
          uncompressed-block-len (.block-length block)
          src-offset (:offset @(.state this))
          remain-len ^long (- block-len src-offset)
          copying-len (Math/min remain-len read-len)
          next-len (- read-len copying-len)
          next-remain (- remain-len copying-len)
          next-offset (if (zero? next-remain) 0 (+ src-offset copying-len))]
      (System/arraycopy block-bytes src-offset b tgt-offset copying-len)
      (when (zero? next-remain)
        (swap! (.state this) update :buf-seq next)
        (swap! (.state this) update :addr + uncompressed-block-len))
      (swap! (.state this) assoc :offset next-offset)
      (if (zero? next-len)
        len
        (recur (+ tgt-offset copying-len) next-len)))))

(defn pbgzfis-read-byte<> [this ^bytes b]
  (pbgzfis-read-byte<>-int-int this b 0 (alength b)))

(defn pbgzfis-read-void [this]
  (let [ba (byte-array 1)]
    (pbgzfis-read-byte<> this ba)
    (bit-and (aget ba 0) 0xFF)))

(defn pbgzfis-available [^cljam.io.util.pbgzf.ParallelBGZFInputStream this]
  (let [blk ^BGZFUncompressedBlock (first (:buf-seq @(.state this)))]
    (alength ^bytes (.bytes blk))))

(defn pbgzfis-getFilePointer [^cljam.io.util.pbgzf.ParallelBGZFInputStream this]
  (let [blk ^BGZFUncompressedBlock (first (:buf-seq @(.state this)))]
    (if (= (:offset @(.state this)) (alength ^bytes (.bytes blk)))
      (bgzf/make-file-pointer (+ (:addr @(.state this)) (.block-length blk)) 0)
      (bgzf/make-file-pointer (:addr @(.state this)) (:offset @(.state this))))))

(defn pbgzfis-seek [^cljam.io.util.pbgzf.ParallelBGZFInputStream this pointer]
  (let [new-addr (bgzf/get-block-address pointer)
        new-offset (bgzf/get-block-offset pointer)]
    (.seek ^RandomAccessFile (:raf @(.state this)) new-addr)
    (swap!
     (.state this)
     assoc
     :buf-seq (read-content (:pool @(.state this)) (:raf @(.state this)))
     :addr new-addr
     :offset new-offset)))

(defn parallel-bgzf-input-stream [f n-threads]
  (cljam.io.util.pbgzf.ParallelBGZFInputStream. f n-threads))

(extend-type cljam.io.util.pbgzf.ParallelBGZFInputStream
  bgzf/IBGZFSeekable
  (available [this]
    (pbgzfis-available this))
  (get-file-pointer [this]
    (pbgzfis-getFilePointer this))
  (seek [this pointer]
    (pbgzfis-seek this pointer)))
