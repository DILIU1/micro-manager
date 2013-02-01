(ns slide-explorer.flatfield
  (:require [clojure.java.io :as io]
            [slide-explorer.tile-cache :as tile-cache]
            [slide-explorer.disk :as disk]
            [slide-explorer.image :as image]
            [slide-explorer.store :as store]))


(defn map-vals [f m]
  (into {}
        (for [[k v] m]
          [k (f v)])))

(defn unzoomed-indices-by-channel [cache-value]
  (->> cache-value
       keys
       (filter #(= 1 (:zoom %)))
       (group-by :nc)))       

(defn images-in-cache [cache indices]
  (map #(tile-cache/get-tile cache % false) indices))

(defn flat-field [images]
  (-> (image/intensity-projection :median images)
      (image/gaussian-blur 50)
      image/normalize-to-max))

(defn flat-field-by-channel [cache]
  (->> @cache
       (unzoomed-indices-by-channel)
       (map-vals (partial images-in-cache cache))
       (map-vals flat-field)))

(defn flatten-image [[index image] flat-field-by-channel]
  (let [correction (flat-field-by-channel (:nc index))]
    (image/divide-processors image correction)))

(defn re-flatten [in-dir out-dir flat-field-by-channel]
  (let [out-cache (tile-cache/create-tile-cache 200 out-dir false)]
    (doseq [[index image] (disk/read-tiles in-dir)]
      (let [flattened (flatten-image [index image] flat-field-by-channel)]
        (store/add-to-memory-tiles out-cache index image 1/256)))))
  

;; testing

(defn test-generate []
  (->> slide-explorer.view/mt
       flat-field-by-channel
       (map-vals image/show)))

(defn transfer [in-cache]
  (let [in-dir (tile-cache/tile-dir in-cache)
        out-dir (io/file in-dir "flat")]
    (re-flatten in-dir out-dir (flat-field-by-channel in-cache))))
        
        
                      
                      

;; flat field determination

(comment 
(def flat-field-positions
  (map #(* 1/8 %) (range -4 5)))

(def flat-field-coords
  (for [x flat-field-positions
        y flat-field-positions]
    [x y]))

(defn flat-field-scaled-coords []
  (let [width (mm/core getImageWidth)
        height (mm/core getImageHeight)]
    (map #(let [[x y] %] (Point. (* width x) (* height y)))
         flat-field-coords))) 

(defn flat-field-stage-coords [scaled-coords]
  (let [transform (origin-here-stage-to-pixel-transform)]
    (map #(affine/inverse-transform % transform)
         scaled-coords)))
    
(defn flat-field-acquire []
  (let [acq-settings (create-acquisition-settings)
        scaled (flat-field-scaled-coords)
        to-and-fro (concat scaled (reverse scaled))]
    (for [coords (flat-field-stage-coords to-and-fro)]
      (acquire-at coords acq-settings))))

(defn flat-field-save [images]
  (let [dir (io/file "flatfield")]
    (.mkdirs dir)
    (dorun
      (loop [images0 (map :proc (flatten images))
             i 0]
        (println i)
        (when-let [image (first images0)]
          (slide-explorer.disk/write-tile dir {:i i} (first images0)))
        (when-let [more (next images0)]
          (recur more (inc i)))))))
)