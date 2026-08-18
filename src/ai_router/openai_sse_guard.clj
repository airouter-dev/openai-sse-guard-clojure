(ns ai-router.openai-sse-guard
  "Bounded, provider-neutral observation of OpenAI-compatible SSE streams.

  The public API is deliberately immutable: `feed` returns a new observer state,
  and the state retains framing metadata only.  It never stores generated text,
  performs a retry, or makes a network request."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.charset Charset CodingErrorAction CharacterCodingException]))

(def ^:private utf8 (Charset/forName "UTF-8"))
(def ^:private byte-array-class (class (byte-array 0)))
(def ^:private default-options
  {:max-frame-bytes 65536
   :max-events 10000
   :max-identifier-length 96})
(def ^:private option-limits
  {:max-frame-bytes [256 (* 4 1024 1024)]
   :max-events [1 1000000]
   :max-identifier-length [8 256]})

;; Longest-first matters when a CRLF boundary is present at the same offset as
;; a shorter CR or LF candidate.
(def ^:private event-boundaries
  [[13 10 13 10]
   [13 10 10]
   [13 10 13]
   [10 13 10]
   [10 10]
   [10 13]
   [13 13 10]
   [13 13]])

(defn- bounded-option
  [opts key]
  (let [fallback (get default-options key)
        value (get opts key fallback)
        value (if (integer? value) value fallback)
        [lower upper] (get option-limits key)]
    (-> value (max lower) (min upper))))

(defn options
  "Normalizes observer limits.

  Supported keys are `:max-frame-bytes`, `:max-events`, and
  `:max-identifier-length`. Values are clamped to package-wide safe bounds.
  This function is public so an application can record the exact limits used
  for an attempt."
  ([] (options {}))
  ([opts]
   (let [opts (or opts {})]
     (into {} (map (fn [key] [key (bounded-option opts key)])
                   (keys default-options))))))

(defn new-observer
  "Creates an empty observer state.

  The state is an ordinary immutable map. Pass it to `feed` or `feed-bytes`,
  then call `finish` before taking a final `snapshot`."
  ([] (new-observer {}))
  ([opts]
   {:options (options opts)
    :buffer []
    :protocol-hint :unknown
    :termination :open
    :has-output? false
    :saw-terminal-event? false
    :event-count 0
    :malformed-event-count 0
    :last-event-type nil
    :error-code nil
    :finished? false}))

(defn- bytes-for
  ^clojure.lang.IPersistentVector
  [chunk]
  (cond
    (string? chunk)
    (vec (.getBytes ^String chunk utf8))

    (instance? byte-array-class chunk)
    (mapv #(bit-and (int %) 0xff) ^bytes chunk)

    (sequential? chunk)
    (mapv #(bit-and (int %) 0xff) chunk)

    :else
    (throw (ex-info "feed expects a UTF-8 string, byte array, or byte sequence"
                    {:type ::invalid-chunk
                     :class (some-> chunk class str)}))))

(defn- decode-utf8
  "Strictly decodes bytes, returning nil for malformed UTF-8."
  [bytes]
  (try
    (let [decoder (doto (.newDecoder utf8)
                    (.onMalformedInput CodingErrorAction/REPORT)
                    (.onUnmappableCharacter CodingErrorAction/REPORT))]
      (.toString (.decode decoder
                          (ByteBuffer/wrap
                           (byte-array (map #(unchecked-byte (int %)) bytes))))))
    (catch CharacterCodingException _ nil)))

(defn- starts-at?
  [bytes index pattern]
  (let [end (+ index (count pattern))]
    (and (<= end (count bytes))
         (= pattern (subvec bytes index end)))))

(defn- find-boundary
  [bytes]
  (loop [index 0]
    (if (>= index (count bytes))
      nil
      (if-let [pattern (first (sort-by count >
                                      (filter #(starts-at? bytes index %)
                                              event-boundaries)))]
        {:index index :length (count pattern)}
        (recur (inc index))))))

(defn- line-value
  [line prefix]
  (let [value (subs line (count prefix))]
    (if (str/starts-with? value " ")
      (subs value 1)
      value)))

(defn- decode-lines
  "Splits at all SSE line endings before decoding each line.

  Doing this byte-first avoids treating CRLF as a single Unicode grapheme and
  keeps malformed UTF-8 from being silently replaced."
  [frame]
  (loop [remaining frame
         line []
         lines []]
    (if (empty? remaining)
      (if-let [decoded (decode-utf8 line)]
        (conj lines decoded)
        nil)
      (let [current (first remaining)
            tail (subvec remaining 1)]
        (cond
          (= current 10)
          (if-let [decoded (decode-utf8 line)]
            (recur tail [] (conj lines decoded))
            nil)

          (= current 13)
          (let [tail (if (= 10 (first tail)) (subvec tail 1) tail)]
            (if-let [decoded (decode-utf8 line)]
              (recur tail [] (conj lines decoded))
              nil))

          :else
          (recur tail (conj line current) lines))))))

(defn- parse-frame
  [frame]
  (when-let [lines (decode-lines frame)]
    (let [event-type (reduce (fn [value line]
                               (if (str/starts-with? line "event:")
                                 (line-value line "event:")
                                 value))
                             nil
                             lines)
          data-lines (keep #(when (str/starts-with? % "data:")
                              (line-value % "data:"))
                           lines)]
      {:event-type (not-empty event-type)
       ;; SSE joins multiple data fields with a newline.
       :data (str/join "\n" data-lines)})))

(defn- bounded-identifier
  [value limit]
  (when (string? value)
    (let [value (str/trim value)]
      (when (and (seq value)
                 (<= (count value) limit)
                 (re-matches #"[A-Za-z0-9._:-]+" value))
        value))))

(defn- error-code-from-json
  [data limit]
  (try
    (let [payload (json/read-str data)
          error (when (map? payload)
                  (or (get payload "error") (get payload :error)))
          value (when (map? error)
                  (or (get error "code")
                      (get error :code)
                      (get error "type")
                      (get error :type)))]
      (bounded-identifier value limit))
    (catch Exception _ nil)))

(defn- error-payload?
  [data]
  (try
    (let [payload (json/read-str data)]
      (and (map? payload)
           (or (contains? payload "error")
               (contains? payload :error))))
    (catch Exception _ false)))

(defn- infer-protocol
  [current event-type data]
  (cond
    (and event-type (str/starts-with? event-type "response.")) :responses
    (or (= data "[DONE]")
        (str/includes? data "\"choices\"")) :chat-completions
    (= current :unknown) current
    :else current))

(defn- fail
  [observer]
  (-> observer
      (assoc :termination :error)
      (update :malformed-event-count inc)))

(defn- consume-frame
  [observer frame]
  (let [{:keys [max-events max-identifier-length]} (:options observer)]
    (if (>= (:event-count observer) max-events)
      (fail observer)
      (let [observer (update observer :event-count inc)
            parsed (parse-frame frame)]
        (if (nil? parsed)
          (fail observer)
          (let [{:keys [event-type data]} parsed
                protocol (infer-protocol (:protocol-hint observer) event-type data)
                error? (or (= event-type "error") (error-payload? data))
                error-code (or (:error-code observer)
                               (when error?
                                 (error-code-from-json data max-identifier-length)))
                observer (assoc observer
                                :protocol-hint protocol
                                :last-event-type (bounded-identifier event-type
                                                                       max-identifier-length)
                                :error-code error-code)
                output? (or (seq data) event-type)]
            (cond
              (= data "[DONE]")
              (assoc observer
                     :termination :done
                     :saw-terminal-event? true
                     :has-output? (or (:has-output? observer) output?))

              (contains? #{"response.incomplete" "response.incomplete_event"}
                         event-type)
              (assoc observer
                     :termination :incomplete
                     :saw-terminal-event? true
                     :has-output? (or (:has-output? observer) output?))

              (contains? #{"response.completed" "response.complete"} event-type)
              (assoc observer
                     :termination :done
                     :saw-terminal-event? true
                     :has-output? (or (:has-output? observer) output?))

              error?
              (assoc observer
                     :termination :error
                     :has-output? true)

              :else
              (assoc observer
                     :has-output? (or (:has-output? observer) output?)))))))))

(defn- drain
  [observer]
  (loop [observer observer]
    (if (not= :open (:termination observer))
      observer
      (if-let [{:keys [index length]} (find-boundary (:buffer observer))]
        (let [frame (subvec (:buffer observer) 0 index)
              remaining (subvec (:buffer observer) (+ index length))]
          (recur (consume-frame (assoc observer :buffer remaining) frame)))
        (if (> (count (:buffer observer)) (get-in observer [:options :max-frame-bytes]))
          (fail observer)
          observer)))))

(defn feed-bytes
  "Adds a transport chunk and returns the updated observer.

  `chunk` may be a Java byte array or a sequence of unsigned byte values.
  Chunk boundaries may occur anywhere, including in a UTF-8 code point or an
  SSE field. Bytes are discarded as soon as their event is consumed."
  [observer chunk]
  (let [observer (or observer (new-observer))]
    (if (or (:finished? observer)
            (not= :open (:termination observer)))
      observer
      (drain (update observer :buffer into (bytes-for chunk))))))

(defn feed
  "Adds a UTF-8 string chunk and returns the updated observer.

  Use `feed-bytes` when the HTTP client exposes raw network bytes."
  [observer chunk]
  (feed-bytes observer chunk))

(defn finish
  "Marks an observer complete and returns its final state.

  A stream without a terminal marker is conservatively reported as
  `:unexpected-eof`; a valid partial frame still counts as output evidence."
  [observer]
  (let [observer (drain (or observer (new-observer)))]
    (if (:finished? observer)
      observer
      (let [observer (if (and (= :open (:termination observer))
                              (seq (:buffer observer)))
                       (if (decode-utf8 (:buffer observer))
                         (assoc observer :buffer [] :has-output? true)
                         (fail (assoc observer :buffer [])))
                       observer)
            observer (if (= :open (:termination observer))
                       (assoc observer :termination :unexpected-eof)
                       observer)]
        (assoc observer :finished? true :buffer [])))))

(defn snapshot
  "Returns bounded metadata suitable for logs or retry policy decisions.

  No frame data, generated text, or provider error prose is included."
  [observer]
  (let [observer (or observer (new-observer))]
    (select-keys observer [:protocol-hint
                           :termination
                           :has-output?
                           :saw-terminal-event?
                           :event-count
                           :malformed-event-count
                           :last-event-type
                           :error-code])))

(defn observe
  "Observes a sequence of UTF-8 string/byte chunks and returns a snapshot.

  An optional second argument supplies the same options accepted by
  `new-observer`."
  ([chunks] (observe chunks {}))
  ([chunks opts]
   (snapshot (finish (reduce feed (new-observer opts) chunks)))))
