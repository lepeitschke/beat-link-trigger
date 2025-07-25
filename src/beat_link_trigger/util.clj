(ns beat-link-trigger.util
  "Provides commonly useful utility functions."
  (:require [clojure.edn]
            [clojure.java.io]
            [clojure.set]
            [clojure.string :as str]
            [me.raynes.fs :as fs]
            [overtone.midi :as midi]
            [seesaw.core :as seesaw]
            [taoensso.timbre :as timbre]
            [thi.ng.color.core :as color])
  (:import [org.deepsymmetry.beatlink CdjStatus CdjStatus$TrackType CdjStatus$TrackSourceSlot
            DeviceAnnouncement DeviceFinder MediaDetails VirtualCdj]
           [org.deepsymmetry.beatlink.data TimeFinder SignatureFinder]
           [org.deepsymmetry.cratedigger.pdb RekordboxAnlz$PhraseLow RekordboxAnlz$PhraseMid RekordboxAnlz$PhraseHigh
            RekordboxAnlz$MoodLowPhrase RekordboxAnlz$MoodMidPhrase RekordboxAnlz$MoodHighPhrase
            RekordboxAnlz$SongStructureEntry RekordboxAnlz$SongStructureTag
            RekordboxAnlz$TrackMood RekordboxAnlz$TrackBank]
           [java.awt Color Font GraphicsEnvironment RenderingHints]
           [java.io File]
           [javax.sound.midi Sequencer Synthesizer]
           [javax.swing JDialog JFrame]
           [java.util.concurrent TimeUnit]
           [jiconfont.icons.font_awesome FontAwesome]
           [jiconfont.swing IconFontSwing]
           [uk.co.xfactorylibrarians.coremidi4j CoreMidiDestination CoreMidiDeviceProvider CoreMidiSource]))

(def ^:private project-version
  (delay (if-let [version-data-file (clojure.java.io/resource "beat_link_trigger/version.edn")]
           (clojure.edn/read-string (slurp version-data-file))
           ;; Return a dummy version, this only happens when reading preferences during CI build.
           {:version "0.0.0-SNAPSHOT-0-0x0", :raw-version "v0.0.0-SNAPSHOT-0-0"})))

(defn get-version
  "Returns the version information set up by lein-v."
  []
  (:version @project-version))

(defn get-java-version
  "Returns the version of Java in which we are running."
  []
  (str (System/getProperty "java.version")
       (when-let [vm-name (System/getProperty "java.vm.name")]
         (str ", " vm-name))
       (when-let [vendor (System/getProperty "java.vm.vendor")]
         (str ", " vendor))))

(defn get-os-version
  "Returns the operating system and version in which we are running."
  []
  (str (System/getProperty "os.name") " " (System/getProperty "os.version")))

(defn get-build-date
  "Returns the date this jar was built, if we are running from a jar."
  []
  (let [a-class    (class get-version)
        class-name (str (.getSimpleName a-class) ".class")
        class-path (str (.getResource a-class class-name))]
    (when (str/starts-with? class-path "jar")
      (let [manifest-path (str (subs class-path 0 (inc (str/last-index-of class-path "!")))
                               "/META-INF/MANIFEST.MF")]
        (with-open [stream (.openStream (java.net.URL. manifest-path))]
          (let [manifest   (java.util.jar.Manifest. stream)
                attributes (.getMainAttributes manifest)]
            (.getValue attributes "Build-Timestamp")))))))

(def ^:private throttle-timestamps
  "Keeps track of the last time we logged about a particular context,
  for the purpose of throttling it. A map from contexts to timestamps."
  (atom {}))

(defn- expire-old-timestamps
  "Called whenever we throttle something, to get rid of entries in our
  timestamp cache for things we haven't seen in an hour, so it does
  not expand forever."
  []
  (swap! throttle-timestamps
         (fn [timestamps]
           (let [now       (System/nanoTime)
                 threshold (.toNanos TimeUnit/HOURS 1)]
             (reduce-kv (fn [acc k v]
                          (if (> (- now v) threshold)
                            (dissoc acc k)
                            acc))
                        timestamps
                        timestamps)))))

(defn throttle
  "Provides a way to avoid spamming logs with something that is likely
  to happen often but is only interesting to hear about once every
  fifteen minutes. `context` is used to uniquely identify the event,
  so it makes sense to provide something like a vector of a keyword
  describing the place in the code and an object that is being
  complained about. Returns truthy if the logging should proceed.

  If provided, `interval` is the number of nanoseconds for which we
  will suppress logging of the same event; the default is one minute.
  In any event, intervals longer than an hour will be treated as an
  hour because the timestamp cache ages out entries older than that."
  ([context]
   (throttle context (.toNanos TimeUnit/MINUTES 1)))
  ([context interval]
   (expire-old-timestamps)
   (let [now     (System/nanoTime)
         updated (swap! throttle-timestamps update context
                        (fn [then]
                          (if (or (nil? then)  ; First we've seen this.
                                  (> (- now then) interval))
                            now
                            then)))]
     (= (get updated context) now))))  ; We are not throttling this time.q


(def ^:private file-types
  "A map from keywords identifying the kinds of files we work with to
  the filename extensions we create them with and require them to
  have."
  {:configuration    "blt"
   :trigger-export   "bltx"
   :metadata-archive "blm"
   :playlist         "csv"
   :show             "bls"})

(def ^:private file-extensions
  "A map from filename extensions we use to the corresponding keyword
  identifying that type of file."
  (clojure.set/map-invert file-types))

(defn extension-for-file-type
  "Given a keyword identifying one of the types of files we work with,
  return the corresponding filename extension we require."
  [file-type]
  (file-types file-type))

(defn file-type-for-extension
  "Given a filename extension, return the keyword identifying it as one
  of the types of files we work with, or `nil` if we don't recognize
  it."
  [extension]
  (file-extensions extension))

(defn file-type
  "Given a file, return the keyword identifying it as one of the types
  of files we work with, or, `nil` if we don't recognize it."
  [file]
  (when-let [extension (fs/extension (clojure.java.io/file file))]
    (file-type-for-extension (subs extension 1))))

(defn trim-extension
  "Removes a file extension from the end of a string."
  [^String s]
  (let [dot (.lastIndexOf s ".")]
    (if (pos? dot) (subs s 0 dot) s)))

(defn confirm-overwrite-file
  "If the specified file already exists, asks the user to confirm that
  they want to overwrite it. If `required-extension` is supplied, that
  extension is added to the end of the filename, if it is not already
  present, before checking. Returns the file to write if the user
  confirmed overwrite, or if a conflicting file did not already exist,
  and `nil` if the user said to cancel the operation. If a non-`nil`
  window is passed in `parent`, the confirmation dialog will be
  centered over it."
  ^java.io.File [^File file ^String required-extension parent]
  (when file
    (let [required-extension (when required-extension
                               (if (.startsWith required-extension ".")
                                 required-extension
                                 (str "." required-extension)))
          file (if (or (nil? required-extension) (.. file (getAbsolutePath) (endsWith required-extension)))
                 file
                 (clojure.java.io/file (str (.getAbsolutePath file) required-extension)))]
      (or (when-not (.exists file) file)
          (let [^JDialog confirm (seesaw/dialog
                                  :content (str "Replace existing file?\nThe file " (.getName file)
                                                " already exists, and will be overwritten if you proceed.")
                                  :type :warning :option-type :yes-no)]
            (.pack confirm)
            (.setLocationRelativeTo confirm parent)
            (let [result (when (= :success (seesaw/show! confirm)) file)]
              (seesaw/dispose! confirm)
              result))))))

(defn confirm
  "Replacement for `seesaw/confirm` which understands that pressing the
  ESC key means the user wants to cancel, not proceed.

  Returns truthy if operation should proceed. If a non-`nil` window is
  passed in `parent`, the confirmation dialog will be centered over
  it."
  [parent text & {:keys [type option-type title]
                  :or   {type        :question
                         option-type :ok-cancel}}]
  (let [^JDialog confirm (seesaw/dialog
                          :content text
                          :type type :option-type option-type :title title)]
    (.pack confirm)
    (.setLocationRelativeTo confirm parent)
    (future  ; Sometimes the dialog ends up hidden behind its parent; looks like a seesaw bug.
      (Thread/sleep 100)
      (seesaw/invoke-later (.toFront confirm)))
    (let [result (= :success (seesaw/show! confirm))]
      (seesaw/dispose! confirm)
      result)))

(def ^DeviceFinder device-finder
  "A convenient reference to the [Beat Link
  `DeviceFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/DeviceFinder.html)
  singleton."
  (DeviceFinder/getInstance))

(def ^VirtualCdj virtual-cdj
  "A convenient reference to the [Beat Link
  `VirtualCdj`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/VirtualCdj.html)
  singleton."
  (VirtualCdj/getInstance))

(def ^TimeFinder time-finder
  "A convenient reference to the [Beat Link
  `TimeFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/TimeFinder.html)
  singleton."
  (TimeFinder/getInstance))

(def ^SignatureFinder signature-finder
  "A convenient reference to the [Beat Link
  `SignatureFinder`](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/data/SignatureFinder.html)
  singleton."
  (SignatureFinder/getInstance))

(defn online?
  "Check whether we are in online mode, with all the required
  beat-link finder objects running."
  []
  (and (.isRunning device-finder) (.isRunning virtual-cdj)))

(defn visible-player-numbers
  "Return the list of players numbers currently visible on the
  network (ignoring our virtual player, and any mixers or rekordbox
  instances)."
  []
  (filter #(< % 16) (map (fn [^DeviceAnnouncement device] (.getDeviceNumber device))
                         (.getCurrentDevices device-finder))))

(defn remove-blanks
  "Converts an empty string to a `nil` value so `or` will reject it."
  [s]
  (when-not (str/blank? s)
    s))

(defn assign-unique-name
  "Picks a name for an element in a list (for example a cue in a track),
  given the names of all the other cues. If no base name is supplied,
  picks \"Untitled\" or \"Untitled <n>\"; if a base name is given,
  appends \" copy\" and a number if necessary to make it
  unique (although if the name already ends with copy it is not added
  again)."
  ([existing-names]
   (assign-unique-name existing-names ""))
  ([existing-names base-name]
   (let [base-name      (str/trim (or base-name ""))
         without-number (if (str/blank? base-name)
                          "Untitled"
                          (str/replace base-name #"\s+\d*$" ""))
         with-copy      (if (str/blank? base-name)
                          without-number
                          (or (re-matches #"(?i).*\s+copy$" without-number)
                              (str base-name " Copy")))
         template       (str/lower-case with-copy)
         quoted         (java.util.regex.Pattern/quote template)
         largest-number (reduce (fn [result name]
                                  (if (= template name)
                                    (max result 1)
                                    (if-let [[_ ^String n] (re-matches (re-pattern (str quoted "\\s+(\\d+)")) name)]
                                      (max result (Long/valueOf n))
                                      result)))
                                0
                                (map (comp str/trim str/lower-case #(or % "")) existing-names))]
     (if (zero? largest-number)
       with-copy
       (str with-copy " " (inc largest-number))))))

(defn paint-placeholder
  "A function which will paint placeholder text in a text field if the
  user has not added any text of their own, since Swing does not have
  this ability built in. Takes the text of the placeholder, the
  component into which it should be painted, and the graphics context
  in which painting is taking place."
  [^String text ^javax.swing.JTextField c ^java.awt.Graphics2D g]
  (when (zero? (.. c (getText) (length)))
    (.setRenderingHint g RenderingHints/KEY_ANTIALIASING RenderingHints/VALUE_ANTIALIAS_ON)
    (.setColor g Color/gray)
    (.drawString g text (.. c (getInsets) left)
                 (+ (.. g (getFontMetrics) (getMaxAscent)) (.. c (getInsets) top)))))

(defn trigger-color
  "Calculates the color that should be used as the background color for
  a trigger row, given the trigger index and show hue (if any).
  `dark?` reflects the current user interface dark mode setting."
  [trigger-index show-hue dark?]
  (let [base-color (case [dark? (odd? trigger-index)]
                     [false false] "#ddd"
                     [false true]  "#eee"
                     [true false]  "#333"
                     [true true]   "#222")]
    (if show-hue
      (let [luminance (-> base-color color/css color/luminance)
            color     (color/hsla (/ show-hue 360.0) 0.95 luminance)]
        (Color. @(color/as-int24 color)))
      base-color)))

(defn show-popup-from-button
  "Displays a popup menu when the gear button is clicked as an
  ordinary mouse event."
  [target ^javax.swing.JPopupMenu popup ^java.awt.event.MouseEvent event]
  (.show popup target (.x (.getPoint event)) (.y (.getPoint event))))

(defmacro case-enum
  "Like Clojure's built-in `case`, but can explicitly dispatch on Java
  enum ordinals."
  {:style/indent 1}
  [e & clauses]
  (letfn [(enum-ordinal [e] `(let [^Enum e# ~e] (.ordinal e#)))]
    `(case ~(enum-ordinal e)
       ~@(concat
          (mapcat (fn [[test result]]
                    [(eval (enum-ordinal test)) result])
                  (partition 2 clauses))
          (when (odd? (count clauses))
            (list (last clauses)))))))

(defmacro doseq-indexed
  "Analogous to Clojure's `map-indexed` for `doseq`: iterate for
  side-effects with an index."
  {:style/indent 2}
  [index-sym [item-sym coll] & body]
  `(doseq [[~index-sym ~item-sym] (map list (range) ~coll)]
     ~@body))

;; Used to represent the available players in the Triggers window
;; Watch menu, and the Load Track on a Player window. The `toString`
;; method tells Swing how to display it, and the number is what we
;; need for comparisons.
(defrecord PlayerChoice [number]
  Object
  (toString [_] (cond
                  (neg? number) "Any Player"
                  (zero? number) "Master Player"
                  :else (str "Player " number))))


;; Used to represent the available MIDI outputs in the output menu. The `toString` method
;; tells Swing how to display it, so we can suppress the CoreMidi4J prefix.
(defrecord MidiChoice [full-name]
  Object
  (toString [_] (str/replace full-name #"^CoreMIDI4J - " "")))

(defonce ^{:doc "Tracks window positions so we can try to restore them
  in the configuration the user had established."}
  window-positions (atom {}))

(defn usable-midi-device?
  "Returns true if a MIDI device should be visible. Filters out non-CoreMidi4J devices when that library
  is active."
  [device]
  (or (not (CoreMidiDeviceProvider/isLibraryLoaded))
      (let [raw-device (:device device)]
        (or (instance? Sequencer raw-device) (instance? Synthesizer raw-device)
            (instance? CoreMidiDestination raw-device) (instance? CoreMidiSource raw-device)))))

(defn get-midi-inputs
  "Returns all available MIDI input devices as menu choice model objects"
  []
  (map #(MidiChoice. (:name %)) (filter usable-midi-device? (sort-by :name (midi/midi-sources)))))

(defn get-midi-outputs
  "Returns all available MIDI output devices as menu choice model objects"
  []
  (map #(MidiChoice. (:name %)) (filter usable-midi-device? (sort-by :name (midi/midi-sinks)))))

(defonce ^{:doc "Holds a map of all the MIDI output devices we have
  opened, keyed by their names, so we can reuse them."}
  opened-outputs
  (atom {}))

(defonce ^{:private true
           :doc "Keeps track of whether we have loaded our custom fonts yet."}
  fonts-loaded
  (atom false))

(defn load-fonts
  "Load and register the fonts we will use to draw on the display, if
  they have not already been."
  []
  (or @fonts-loaded
      (let [ge (GraphicsEnvironment/getLocalGraphicsEnvironment)]
        (doseq [font-file ["/fonts/DSEG/DSEG7Classic-Regular.ttf"
                           "/fonts/Orbitron/Orbitron-Black.ttf"
                           "/fonts/Orbitron/Orbitron-Bold.ttf"
                           "/fonts/Teko/Teko-Regular.ttf"
                           "/fonts/Teko/Teko-SemiBold.ttf"]]
            (.registerFont ge (Font/createFont Font/TRUETYPE_FONT
                                               (.getResourceAsStream MidiChoice font-file))))
        (IconFontSwing/register (FontAwesome/getIconFont))
        (reset! fonts-loaded true))))

(defn save-window-position
  "Saves the position of a window under the specified keyword. If
  `no-size?` is supplied with a `true` value, only the location is
  saved, and the window size is not recorded."
  ([window k]
   (save-window-position window k false))
  ([^JFrame window k no-size?]
   (swap! window-positions assoc k (if no-size?
                                     [(.getX window) (.getY window)]
                                     [(.getX window) (.getY window) (.getWidth window) (.getHeight window)]))))

(defn restore-window-position
  "Tries to put a window back where in the position where it was saved
  (under the specified keyword). If no saved position is found, or if
  the saved position has the top left or midpoint of the window
  off-screen, the window is instead positioned centered on the
  supplied parent window; if `parent` is nil, `window` is centered on
  the screen."
  [^JFrame window k parent]
  (let [[x y width height] (get @window-positions k)
        mid-x              (when x (Math/round (+ x (/ (or width (.getWidth window)) 2.0))))
        mid-y              (when y (Math/round (+ y (/ (or height (.getHeight window)) 2.0))))]
    (if (or (nil? x)
            (empty? (filter (fn [^java.awt.GraphicsDevice device]
                              (let [bounds (.. device getDefaultConfiguration getBounds)]
                                (and (.contains bounds x y)
                                     (.contains bounds mid-x mid-y))))
                            (.. java.awt.GraphicsEnvironment getLocalGraphicsEnvironment getScreenDevices))))
      (.setLocationRelativeTo window parent)
      (if (nil? width)
        (.setLocation window x y)
        (.setBounds window x y width height)))))

(defn get-display-font
  "Find one of the fonts configured for use by keyword, which must be
  one of `:orbitron`, `:segment`, or `:teko`. The `style`
  argument is a `java.awt.Font` style constant, and `size` is point
  size.

  Orbitron is only available in bold, but asking for bold gives you
  Orbitron Black. Segment is only available in plain. Teko is available
  in plain and bold (but we actually deliver the semibold version,
  since that looks nicer in the UI)."
  [k style size]
  (case k
    :orbitron (Font. (if (= style Font/BOLD) "Orbitron Black" "Orbitron") Font/BOLD size)
    :segment (Font. "DSEG7 Classic" Font/PLAIN size)
    :teko (Font. (if (= style Font/BOLD) "Teko SemiBold" "Teko") Font/PLAIN size)))

(defn media-contents
  "Returns a string describing the number of tracks and playlists
  present in mounted media found on the network, if that information
  is available, or an empty string otherwise."
  [^MediaDetails details]
  (if (some? details)
    (str (when (pos? (.trackCount details)) (str ", " (.trackCount details) " tracks"))
         (when (pos? (.playlistCount details)) (str ", " (.playlistCount details) " playlists")))
    ""))

(defn generic-media-resource
  "Returns the path to a PNG image resource that can be used as generic
  album artwork for the type of media being played in the specified
  player number, if any."
  [^Long player]
  (let [^CdjStatus status (when (.isRunning virtual-cdj) (.getLatestStatusFor virtual-cdj player))
        xdj-xz            (when status (str/starts-with? (.getDeviceName status) "XDJ-XZ"))
        slot              (when status (.getTrackSourceSlot status))]
    (cond
      (nil? status)
      "images/NoTrack.png"

      (= CdjStatus$TrackType/CD_DIGITAL_AUDIO (.getTrackType status))
      "images/CDDAlogo.png"

      (and xdj-xz (= slot CdjStatus$TrackSourceSlot/SD_SLOT))
      "images/USB.png"  ; The XDJ-XZ has two USB slots, one pretends to be SD for backwards compatibility.

      :else
      (case-enum slot

        CdjStatus$TrackSourceSlot/CD_SLOT
        "images/CD_data_logo.png"

        CdjStatus$TrackSourceSlot/COLLECTION
        "images/Collection_logo.png"

        CdjStatus$TrackSourceSlot/NO_TRACK
        "images/NoTrack.png"

        CdjStatus$TrackSourceSlot/SD_SLOT
        "images/SD.png"

        CdjStatus$TrackSourceSlot/USB_SLOT
        "images/USB.png"

        "images/UnknownMedia.png"))))

(defn track-bank-name
  "Given a song structure tag parsed from a track, returns the bank that
  was assigned to the track in a nicely formatted way."
  [^RekordboxAnlz$SongStructureTag tag]
  (case-enum (.bank (.body tag))
    RekordboxAnlz$TrackBank/CLUB_1  "Club 1"
    RekordboxAnlz$TrackBank/CLUB_2  "Club 2"
    RekordboxAnlz$TrackBank/COOL    "Cool"
    RekordboxAnlz$TrackBank/DEFAULT "(Cool)"
    RekordboxAnlz$TrackBank/HOT     "Hot"
    RekordboxAnlz$TrackBank/NATURAL "Natural"
    RekordboxAnlz$TrackBank/SUBTLE  "Subtle"
    RekordboxAnlz$TrackBank/VIVID   "Vivid"
    RekordboxAnlz$TrackBank/WARM    "Warm"
    "Unknown?"))

(defn track-bank-keyword
  "Given a song structure tag parsed from a track, returns the bank that
  was assigned to the track as a keyword for matching in code."
  [^RekordboxAnlz$SongStructureTag tag]
  (try
    (case-enum (.bank (.body tag))
               RekordboxAnlz$TrackBank/CLUB_1  :club-1
               RekordboxAnlz$TrackBank/CLUB_2  :club-2
               RekordboxAnlz$TrackBank/COOL    :cool
               RekordboxAnlz$TrackBank/DEFAULT :cool
               RekordboxAnlz$TrackBank/HOT     :hot
               RekordboxAnlz$TrackBank/NATURAL :natural
               RekordboxAnlz$TrackBank/SUBTLE  :subtle
               RekordboxAnlz$TrackBank/VIVID   :vivid
               RekordboxAnlz$TrackBank/WARM    :warm
               (timbre/error "Unrecognized track bank" (.bank (.body tag))))
    (catch NullPointerException e
      (when (throttle [:bad-track-bank tag])
        (timbre/error e "Unable to determine track bank! tag:" tag
                      "body:" (when tag (.body tag))
                      "bank:" (when (and tag (.body tag)) (.bank (.body tag))))))))

(defn track-mood-name
  "Given a song structure tag parsed from a track, returns the mood that
  was detected for the track in a nicely formatted way."
  [^RekordboxAnlz$SongStructureTag tag]
  (case-enum (.mood (.body tag))
    RekordboxAnlz$TrackMood/HIGH "High"
    RekordboxAnlz$TrackMood/MID "Mid"
    RekordboxAnlz$TrackMood/LOW "Low"
    "???"))

(defn phrase-type-keyword
  "Given a song structure entry parsed from a track, returns the keyword
  that represents its unique phrase type for matching in code."
  [^RekordboxAnlz$SongStructureEntry entry]
  (case-enum (.mood (._parent entry))

    RekordboxAnlz$TrackMood/LOW
    (let [^RekordboxAnlz$PhraseLow kind (.kind entry)]
      (case-enum (.id kind)
        RekordboxAnlz$MoodLowPhrase/INTRO    :low-intro
        RekordboxAnlz$MoodLowPhrase/VERSE_1  :low-verse-1
        RekordboxAnlz$MoodLowPhrase/VERSE_1B :low-verse-1
        RekordboxAnlz$MoodLowPhrase/VERSE_1C :low-verse-1
        RekordboxAnlz$MoodLowPhrase/VERSE_2  :low-verse-2
        RekordboxAnlz$MoodLowPhrase/VERSE_2B :low-verse-2
        RekordboxAnlz$MoodLowPhrase/VERSE_2C :low-verse-2
        RekordboxAnlz$MoodLowPhrase/BRIDGE   :low-bridge
        RekordboxAnlz$MoodLowPhrase/CHORUS   :low-chorus
        RekordboxAnlz$MoodLowPhrase/OUTRO    :low-outro

        (when (throttle [:bad-track-phrase entry])
          (timbre/error "Unrecognized low-mood phrase type" kind))))

    RekordboxAnlz$TrackMood/MID
    (let [^RekordboxAnlz$PhraseMid kind (.kind entry)]
      (case-enum (.id kind)
        RekordboxAnlz$MoodMidPhrase/INTRO   :mid-intro
        RekordboxAnlz$MoodMidPhrase/VERSE_1 :mid-verse-1
        RekordboxAnlz$MoodMidPhrase/VERSE_2 :mid-verse-2
        RekordboxAnlz$MoodMidPhrase/VERSE_3 :mid-verse-3
        RekordboxAnlz$MoodMidPhrase/VERSE_4 :mid-verse-4
        RekordboxAnlz$MoodMidPhrase/VERSE_5 :mid-verse-5
        RekordboxAnlz$MoodMidPhrase/VERSE_6 :mid-verse-6
        RekordboxAnlz$MoodMidPhrase/BRIDGE  :mid-bridge
        RekordboxAnlz$MoodMidPhrase/CHORUS  :mid-chorus
        RekordboxAnlz$MoodMidPhrase/OUTRO   :mid-outro

        (when (throttle [:bad-track-phrase entry])
          (timbre/error "Unrecognized mid-mood phrase type" kind))))

    RekordboxAnlz$TrackMood/HIGH
    (let [^RekordboxAnlz$PhraseHigh kind (.kind entry)]
      (case-enum (.id kind)
        RekordboxAnlz$MoodHighPhrase/INTRO  (if (= 1 (.k1 entry)) :high-intro-1 :high-intro-2)
        RekordboxAnlz$MoodHighPhrase/UP     (if (zero? (.k2 entry))
                                              (if (zero? (.k3 entry)) :high-up-1 :high-up-2)
                                              :high-up-3)
        RekordboxAnlz$MoodHighPhrase/DOWN   :high-down
        RekordboxAnlz$MoodHighPhrase/CHORUS (if (= 1 (.k1 entry)) :high-chorus-1 :high-chorus-2)
        RekordboxAnlz$MoodHighPhrase/OUTRO  (if (= 1 (.k1 entry)) :high-outro-1 :high-outro-2)

        (when (throttle [:bad-track-phrase entry])
          (timbre/error "Unrecognized high-mood phrase type" kind))))

    (when (throttle [:bad-track-phrase entry])
      (timbre/error "Unrecognized track mood phrase type" (.mood (._parent entry))))))

(defn players-signature-set
  "Given a map from player number to signature, returns the the set of
  player numbers whose value matched a particular signature."
  [player-map signature]
  (reduce (fn [result [k v]]
            (if (= v signature)
              (conj result k)
              result))
          #{}
          player-map))

(defn players-phrase-uuid-set
  "Given a map from player number to map of chosen phrase trigger UUIDs
  to their beat fit information, returns the the set of player numbers
  whose value included a particular uuid."
  [player-map uuid]
  (reduce (fn [result [k uuid-map]]
            (if (contains? uuid-map uuid)
              (conj result k)
              result))
          #{}
          player-map))

(defn inspect-overflowed
  "Tell the user their attempt to inspect something has resulted in a
  stack overflow, which probably means there is a cycle and they need
  to use another means of exploring it."
  []
  (seesaw/alert (str "<html>A stack overflow occurred while trying to inspect your data. This probably means<br>"
                     "that there is a cycle in the data which causes the inspector to loop forever.<br><br>"
                     "If you want to inspect it, you will need to turn on the nREPL server with CIDER<br>"
                     "handler, connect CIDER, and use cider-inspect to explore it interactively.")
                :title "Inspection Failed, Cycle in Data?" :type :error))

(defn inspect-failed
  "Tell the user their attempt to inspect something has crashed for some
  unkown reason, as described by throwable `t`."
  [t]
  (timbre/warn t "Inspection failed")
  (seesaw/alert (str "<html>A problem occurred while trying to inspect your data.<br><br>" t)
                :title "Inspection Failed" :type :error))

;;; "A poor man's interval tree, from http://clj-me.cgrand.net/2012/03/16/a-poor-mans-interval-tree/
;;; turns out to offer exactly the API I need for figuring out which cues overlap a beat. I only needed
;;; to extend it a slight amount in order to also find cues whose intervals overlap each other.

(defn interval-lt
  "A partial order on intervals and points, where an interval is defined
  by the vector [from to) (notation I recall meaning it includes the
  lower bound but excludes the upper bound), and either can be `nil`
  to indicate negative or positive infinity. A single point at `n` is
  represented by `[n n]`."
  [[a b] [c _]]
  (boolean (and b c
                (if (= a b)
                  (neg? (compare b c))
                  (<= (compare b c) 0)))))

(def empty-interval-map
  "An interval map with no content."
  (sorted-map-by interval-lt [nil nil] #{}))

(defn- isplit-at
  "Splits the interval map at the specified value, unless it already has
  a boundary there."
  [interval-map x]
  (if x
    (let [[[a b :as k] vs] (find interval-map [x x])]
      (if (or (= a x) (= b x))
        interval-map
        (-> interval-map (dissoc k) (assoc [a x] vs [x b] vs))))
    interval-map))

(defn matching-subsequence
  "Extracts the sequence of key, value pairs from the interval map which
  cover the supplied range (either end of which can be `nil`, meaning
  from the beginning or to the end)."
  [interval-map from to]
  (cond
    (and from to)
    (subseq interval-map >= [from from] < [to to])
    from
    (subseq interval-map >= [from from])
    to
    (subseq interval-map < [to to])
    :else
    interval-map))

(defn- ialter
  "Applies the specified function and arguments to all intervals that
  fall within the specified range in the interval map, splitting it at
  each end if necessary so that the exact desired range can be
  affected."
  [interval-map from to f & args]
  (let [interval-map (-> interval-map (isplit-at from) (isplit-at to))
        kvs          (for [[r vs] (matching-subsequence interval-map from to)]
                       [r (apply f vs args)])]
    (into interval-map kvs)))

(defn iassoc
  "Add a value to the specified range in an interval map."
  [interval-map from to v]
  (ialter interval-map from to conj v))

(defn idissoc
  "Remove a value from the specified range in an interval map. If you
  are going to be using this function much, it might be worth adding
  code to consolidate ranges that are no longer distinct."
  [interval-map from to v]
  (ialter interval-map from to disj v))

(defn iget
  "Find the values that are associated with a point or interval within
  the interval map. Calling with a single number will look up the
  values associated with just that point; calling with two arguments,
  or with an interval vector, will return the values associated with
  any interval that overlaps the supplied interval."
  ([interval-map x]
   (if (vector? x)
     (let [[from to] x]
       (iget interval-map from to))
     (get interval-map [x x])))  ; Yes, `get`, not `iget`, don't try to "fix" this, you'll break it!
  ([interval-map from to]
   (reduce (fn [result [_ vs]]
             (clojure.set/union result vs))
           #{}
           (take-while (fn [[[start]]] (< (or start (dec to)) to)) (matching-subsequence interval-map from nil)))))

;;; Support for simulating events

(def ^:dynamic *simulating*
  "Tells the expression convenience value functions that simulation is in
  progress so they should return a random value rather than trying to
  look at actual player context, and provides information about the
  track and point in time that is being simulated."
  nil)

(defn tracks-for-simulation
  "Return a list of all the candidate tracks that can be used to set up a
  simulation context, which is a list of integers (representing the
  sample tracks embedded in Beat Link Trigger) and tuples of show
  files and track signatures (representing all tracks present in all
  open shows). If `phrases-required?` is `true`, then only candidates
  with song structure information are returned."
  ([]
   (tracks-for-simulation false))
  ([phrases-required?]
   (let [shows   @@(requiring-resolve 'beat-link-trigger.show-util/open-shows)
         samples @@(requiring-resolve 'beat-link-trigger.overlay/sample-track-data)]
     (cond->> (apply concat
                     (keys samples)
                     (for [file (keys shows)]
                       (map (fn [signature] [file signature]) (keys (get-in shows [file :tracks])))))
       phrases-required?
       (filter (fn [entry]
                 (if (number? entry)
                   (get-in samples [entry :phrases])
                   (let [[file signature] entry]
                     (get-in shows [file :tracks signature :song-structure])))))))))

(defn data-for-simulation
  "Finds the track metadata for a track about to be simulated. If `:entry`
  is supplied, it holds either a number representing one of the sample
  tracks embedded in BLT, or a tuple of [file signature] identifying a
  track in an open show. If not supplied, a random track will be
  chosen using `tracks-for-simulation` and the value (if any) passed
  with `:phrases-required?`."
  [& {:keys [entry phrases-required?]}]
  (let [entry (or entry (rand-nth (tracks-for-simulation phrases-required?)))]
    (if (number? entry)
      (let [sample (get @@(requiring-resolve 'beat-link-trigger.overlay/sample-track-data) entry)]
        (merge (select-keys sample [:cue-list :metadata :preview :detail :signature])
               {:song-structure    (:phrases sample)
                :grid              (:beat-grid sample)
                :phrases-required? (boolean phrases-required?)}))
      (let [[file signature] entry
            show             (get @@(requiring-resolve 'beat-link-trigger.show-util/open-shows) file)
            track            (get-in show [:tracks signature])
            track-root       ((requiring-resolve 'beat-link-trigger.show-util/build-track-path) show signature)]
        (merge (select-keys track [:grid :metadata :song-structure])
               {:cue-list          ((requiring-resolve 'beat-link-trigger.show-util/read-cue-list) track-root)
                :preview           (.getWaveformPreview ((:preview track)))
                :detail            ((requiring-resolve 'beat-link-trigger.show-util/read-detail) track-root)
                :phrases-required? (boolean phrases-required?)})))))

(defn- phrase-beat-range
  "Once `*simulating*` has been set up with track data to be simulated
  for a track with phrase analysis, returns the beats at which the
  analysis starts and ends."
  []
  (let [body       (.body (:song-structure *simulating*))
        start-beat (.beat (first (sort-by (fn [^RekordboxAnlz$SongStructureEntry entry] (.beat entry))
                                          (.entries body))))
        end-beat   (.endBeat body)]
    [start-beat end-beat]))

(defn time-for-simulation
  "Once `*simulating*` has been set up with track data to be simulated,
  chooses a random time within that track for a status packet to be
  created, and adds it to the map. If phrases are required, ensures
  the time falls within a track phrase."
  []
  (let [{:keys [metadata grid phrases-required?]} *simulating*]
    (if phrases-required?
      (let [[start-beat end-beat] (phrase-beat-range)
            start-ms              (.getTimeWithinTrack grid start-beat)
            end-ms                (.getTimeWithinTrack grid end-beat)]
        (assoc *simulating* :time (+ start-ms (rand-int (- end-ms start-ms)))))
      (assoc *simulating* :time (inc (rand-int (* 1000 (:duration metadata))))))))

(defn beat-for-simulation
  "Once `*simulating*` has been set up with track data to be simulated,
  chooses a random beat within that track for a beat packet to be
  created, and adds it (and the time it falls within the track) to the
  map. If phrases are required, ensures the beat falls within a track
  phrase."
  []
  (let [{:keys [grid phrases-required?]} *simulating*
        beat                             (if phrases-required?
                                           (let [[start-beat end-beat] (phrase-beat-range)]
                                             (+ start-beat (rand-int (- end-beat start-beat))))
                                           (inc (rand-int (.findBeatAtTime grid Long/MAX_VALUE))))]
    (merge *simulating*
           {:beat beat
            :time (.getTimeWithinTrack grid beat)})))

(def ^:private packet-header
  "The byte sequence that begins any device update packet."
  (byte-array 10 (map byte [0x51 0x73 0x70 0x74 0x31 0x57 0x6d 0x4a 0x4f 0x4c])))

(defn- create-device-update-packet
  "Used in creating simulated device update objects, sets up the fields
  which are present in any such packet, then creates the datagram
  packet that can be used to instantiate the Beat Link wrapper.
  `buffer` is a byte array of the correct size for the object to be
  created which has already had the object-specific fields
  initialized."
  [^bytes buffer options]
  (let [{:keys [^java.net.InetAddress address ^String device-name ^int device-number ^int port]
         :or   {address       (java.net.InetAddress/getLocalHost)
                device-name   "Simulator"
                device-number 1
                port          50001}} options]
    (System/arraycopy packet-header 0 buffer 0 (count packet-header))
    (doseq [[^int i ^byte b] (take 20 (map-indexed (fn [i c] [(+ i 0x0b) (byte c)]) device-name))]
      (aset buffer i b))
    (aset buffer 0x1f (byte 1))
    (aset buffer 0x21 (byte device-number))
    (org.deepsymmetry.beatlink.Util/numberToBytes (- (count buffer) 0x24) buffer 0x22 2)
    (java.net.DatagramPacket. buffer (count buffer) address port)))

(defn simulate-beat
  "Create a [`Beat`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/Beat.html)
  for simulating triggers."
  ([]
   (simulate-beat {}))
  ([options]
   (let [buffer (byte-array 96)
         {:keys [pitch bpm beat device-number]
          :or   {pitch         1048576
                 bpm           12800
                 beat          1
                 device-number 1}} options]
     (org.deepsymmetry.beatlink.Util/numberToBytes pitch buffer 85 3)
     (org.deepsymmetry.beatlink.Util/numberToBytes bpm buffer 90 2)
     (aset buffer 0x5c (byte beat))
     (aset buffer 0x5f (byte device-number))
     (org.deepsymmetry.beatlink.Beat. (create-device-update-packet buffer options)))))

(defn simulate-player-status
  "Create a [`CdjStatus`
  object](https://deepsymmetry.org/beatlink/apidocs/org/deepsymmetry/beatlink/CdjStatus.html)
  for simulating triggers."
  ([]
   (simulate-player-status {}))
  ([options]
   (let [buffer (byte-array 0xd4)
         {:keys [pitch bpm bb beat device-number a d-r s-r t-r rekordbox track p-1 f p-2 p-3 packet]
          :or   {pitch         1048576
                 bpm           12800
                 bb            1
                 beat          42
                 device-number 1
                 a             0
                 d-r           0
                 s-r           0
                 t-r           0
                 rekordbox     0
                 track         0
                 p-1           0
                 f             0
                 p-2           0
                 p-3           0
                 packet        1}} options]
     (aset buffer 0x20 (byte 3))
     (aset buffer 0x24 (byte device-number))
     (aset buffer 0x27 (byte a))
     (aset buffer 0x28 (byte d-r))
     (aset buffer 0x29 (byte s-r))
     (aset buffer 0x2a (byte t-r))
     (org.deepsymmetry.beatlink.Util/numberToBytes rekordbox buffer 0x2c 4)
     (org.deepsymmetry.beatlink.Util/numberToBytes track buffer 0x32 2)
     (aset buffer 0x7b (byte p-1))
     (aset buffer 0x89 (byte f))
     (aset buffer 0x8b (byte p-2))
     (org.deepsymmetry.beatlink.Util/numberToBytes pitch buffer 0x8c 4)
     (org.deepsymmetry.beatlink.Util/numberToBytes bpm buffer 0x92 2)
     (org.deepsymmetry.beatlink.Util/numberToBytes pitch buffer 0x98 4)
     (aset buffer 0x9d (byte p-3))
     (org.deepsymmetry.beatlink.Util/numberToBytes beat buffer 0xa0 4)
     (aset buffer 0xa6 (byte bb))
     (org.deepsymmetry.beatlink.Util/numberToBytes pitch buffer 0xc0 4)
     (org.deepsymmetry.beatlink.Util/numberToBytes pitch buffer 0xc4 4)
     (org.deepsymmetry.beatlink.Util/numberToBytes packet buffer 0xc8 4)
     (aset buffer 0xcc (byte 0x0f))
     (org.deepsymmetry.beatlink.CdjStatus. (create-device-update-packet buffer options)))))

(defn in-triggers-ns
  "Helper function for REPL users to switch to the namespace used by
  Triggers windows expressions."
  []
  (println "Switching to expressions namespace for the Triggers window.")
  (in-ns ((requiring-resolve 'beat-link-trigger.expressions/expressions-namespace) nil)))

(defn in-core-ns
  "Helper function for REPL users to switch back to the core namespace,
  the default when opening a REPL into Beat Link Trigger."
  []
  (println "Switching to the default initial namespace.")
  (in-ns 'beat-link-trigger.core))
