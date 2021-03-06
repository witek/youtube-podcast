(ns youtube-podcast.main
  (:require
   [cheshire.core :as json]
   [clojure.java.shell :as shell]
   [clojure.java.io :as io]
   [clojure.xml :as xml]
   [clojure.edn :as edn]
   [clojure.pprint :as pprint]
   [claudio.id3 :as id3]))


(defn write-edn-file [session name data]
  (spit (str (-> session :path) "/" name) (with-out-str (pprint/pprint data))))

(def broken-titles
  #{"Private video"
    "Deleted video"})

(defn load-channel-videos [session]
  (println "Loading Channel...")
  (let [items (-> (str "https://www.googleapis.com/youtube/v3/playlistItems"
                       "?part=contentDetails,snippet"
                       "&maxResults=" 50
                       "&key=" (-> session :youtube :api-key)
                       "&playlistId=" (-> session :youtube :channel-id))
                  slurp
                  (json/parse-string true)
                  :items)
        items (remove #(broken-titles (get-in % [:snippet :title])) items)]
    (write-edn-file session "items.edn" items)
    (assoc session :items items)))


(defn video-id->url [video-id]
  (str "https://www.youtube.com/watch?v=" video-id))


(defn tag-mp3 [file title]
  (id3/write-tag! file
                  :title title
                  :artist nil
                  :album "Youtube Podcast"
                  :genre "Youtube Video"))


(defn download-video [session video-id title]
  (println "  -> Downloading:" title)
  (let [ret (shell/sh "youtube-dl"
                      "--id"
                      "--no-progress"
                      "--continue"
                      "--no-mtime"
                      "--write-thumbnail"
                      "--extract-audio"
                      "--audio-format" "mp3"
                      (video-id->url video-id)
                      :dir (-> session :path))]
    (when-not (= 0 (:exit ret))
      (throw (ex-info (str "youtube-dl failed: "
                           (-> ret :err))
                      {:ret ret :video-id video-id})))))


(defn download-missing-file [session item]
  (let [video-id (get-in item [:contentDetails :videoId])
        file (io/as-file (str (-> session :path) "/" video-id ".mp3"))
        file-exists (.exists file)
        title (get-in item [:snippet :title])]
    #_(println "\n -> " title)
    (when-not file-exists
      (try
        (download-video session video-id title)
        (tag-mp3 file title)
        (catch Exception ex
          (println "  FAILED: " (-> ex .getMessage)))))))


(defn download-missing-files [session]
  (doall
   (for [item (-> session :items)]
     (download-missing-file session item)))
  session)


(defn ->ascii [s]
  (when s
    (.trim
     (apply str (filter #(and
                          (not (= 38 (int %)))
                          (or
                           (<= 32 (int %) 126)
                           (= 10 (int %))))
                        s)))))

(def dateformat-in (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss'Z'" java.util.Locale/US))
(def dateformat-out (java.text.SimpleDateFormat. "EE, dd MMM yyyy HH:mm:ss ZZ" java.util.Locale/US))

(comment
  (-> dateformat-in (.parse "2021-01-05T12:18:08Z")))

(defn create-rss-item [podcast-base-url item]
  (let [title (->ascii (get-in item [:snippet :title]))
        description (->ascii (get-in item [:snippet :description]))
        video-id (get-in item [:contentDetails :videoId])
        file (io/as-file (str video-id ".mp3"))
        length (.length file)
        date-in (get-in item [:snippet :publishedAt])
        date (-> dateformat-in (.parse date-in))
        date-out (-> dateformat-out (.format date))]
    {:tag :item
     :content [{:tag :title :content [title]}
               {:tag :pubDate :content [date-out]}
               {:tag :description :content [description]}
               {:tag :guid :attrs {:isPermaLink false} :content [(str "youtubepodcast-" video-id)]}
               {:tag :enclosure :attrs {:url (str podcast-base-url video-id ".mp3")
                                        :length length
                                        :type "audio/mp3"}}
               {:tag "itunes:image" :attrs {:href (str podcast-base-url video-id ".jpg")}}]}))


(defn create-rss [session]
  (let [podcast-title "Youtube Podcast"
        podcast-description "Youtube Podcast"
        podcast-image "https://www.chirbit.com/images/learn-more-youtube-to-audio.png"
        podcast-base-url (get-in session [:base-url])]
    (with-out-str
      (xml/emit {:tag :rss
                 :attrs {:version "2.0"
                         "xmlns:itunes" "http://www.itunes.com/dtds/podcast-1.0.dtd"}
                 :content [{:tag :channel
                            :content (into [{:tag :title :content [podcast-title]}
                                            {:tag :description :content [podcast-description]}
                                            {:tag "itunes:image" :attrs {:href podcast-image}}
                                            {:tag :image :content [{:tag :url :content [podcast-image]}
                                                                   {:tag :title :content [podcast-title]}
                                                                   {:tag :link :content [podcast-image]}]}
                                            {:tag :link :content [podcast-base-url]}]
                                           (map (partial create-rss-item podcast-base-url)
                                                (-> session :items)))}]}))))


(defn write-feed [session]
  (println "Writing RSS Feed...")
  (spit (str (-> session :path) "/feed.rss.xml")
        (create-rss session)))


(defn new-session [path]
  (println "[" path "]")
  (let [config (edn/read-string (slurp (str path  "/youtube-podcast.edn")))]
    (assoc config
           :path path)))


(defn load! [path]
  (-> (new-session path)
      load-channel-videos
      download-missing-files
      write-feed))

(defn -main [path]
  (load! path))

#_(load! "/home/witek/inbox/youtube-podcast")

;(println (->ascii "hallo & welt\nx"))
;(load!)
