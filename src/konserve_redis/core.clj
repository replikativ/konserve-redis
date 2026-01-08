(ns konserve-redis.core
  "Redis based konserve backend."
  (:require [clojure.core.async :refer [go]]
            [konserve.impl.defaults :refer [connect-default-store]]
            [konserve.impl.storage-layout :refer [PBackingStore PBackingBlob PBackingLock
                                                  PMultiWriteBackingStore PMultiReadBackingStore
                                                  -delete-store header-size]]
            [konserve.utils :refer [async+sync *default-sync-translation*]]
            [konserve.store :as store]
            [superv.async :refer [go-try-]]
            [taoensso.timbre :refer [info warn]]
            [taoensso.carmine :as car :refer [wcar]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]
           [java.util Arrays]))

#_(set! *warn-on-reflection* 1)

(def ^:const output-stream-buffer-size (* 1024 1024))

(defn redis-client
  [{:keys [pool ssl-fn uri]}]
  (merge {:spec (merge {:uri uri}
                       (when-not (= ssl-fn :none)
                         {:ssl-fn (or ssl-fn :default)}))}
         (when-not (= pool :none)
           {:pool (car/connection-pool (or pool {}))})))

(defn put-object [client ^String key ^bytes bytes]
  (wcar client (car/set key bytes)))

(defn get-object [client key]
  (wcar client (car/get key)))

(defn exists? [client key]
  (pos? (wcar client (car/exists key))))

(defn list-objects
  [client]
  (wcar client (car/keys "*")))

(defn copy [client source-key destination-key]
  ;; TODO figure out how to use car/copy
  (let [val (wcar client (car/get source-key))]
    (wcar client
          (car/set destination-key val)
          (car/del source-key))))

(defn delete [client key]
  (wcar client (car/del key)))

(defn mget-objects
  "Fetch multiple keys in a single MGET call.
   Returns values in the same order as the input keys.
   Missing keys return nil in their position."
  [client keys]
  (when (seq keys)
    (wcar client (apply car/mget keys))))

(defn mdelete
  "Delete multiple keys in a single DEL call.
   Returns the number of keys deleted."
  [client keys]
  (when (seq keys)
    (wcar client (apply car/del keys))))

(extend-protocol PBackingLock
  Boolean
  (-release [_ env]
    (if (:sync? env) nil (go-try- nil))))

(defrecord RedisBlob [store key data fetched-object]
  PBackingBlob
  (-sync [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (let [{:keys [header meta value]} @data
                               baos (ByteArrayOutputStream. output-stream-buffer-size)]
                           (if (and header meta value)
                             (do
                               (.write baos header)
                               (.write baos meta)
                               (.write baos value)
                               (put-object (:client store)
                                           key
                                           (.toByteArray baos))
                               (.close baos))
                             (throw (ex-info "Updating a row is only possible if header, meta and value are set."
                                             {:data @data})))
                           (reset! data {})))))
  (-close [_ env]
    (if (:sync? env) nil (go-try- nil)))
  (-get-lock [_ env]
    (if (:sync? env) true (go-try- true)))                       ;; May not return nil, otherwise eternal retries
  (-read-header [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                    ;; first access is always to header, after it is cached
                 (when-not @fetched-object
                   (reset! fetched-object (get-object (:client store) key)))
                 (Arrays/copyOfRange ^bytes @fetched-object (int 0) (int header-size)))))
  (-read-meta [_ meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (Arrays/copyOfRange ^bytes @fetched-object (int header-size) (int (+ header-size meta-size))))))
  (-read-value [_ meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (let [obj ^bytes @fetched-object]
                   (Arrays/copyOfRange obj (int (+ header-size meta-size)) (int (alength obj)))))))
  (-read-binary [_ meta-size locked-cb env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (let [obj ^bytes @fetched-object]
                   (locked-cb {:input-stream
                               (ByteArrayInputStream.
                                (Arrays/copyOfRange obj (int (+ header-size meta-size)) (int (alength obj))))
                               :size (- (alength obj) (+ header-size meta-size))})))))

  (-write-header [_ header env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (swap! data assoc :header header))))
  (-write-meta [_ meta env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (swap! data assoc :meta meta))))
  (-write-value [_ value _meta-size env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (swap! data assoc :value value))))
  (-write-binary [_ _meta-size blob env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (swap! data assoc :value blob)))))

(defrecord RedisStore [client]
  PBackingStore
  (-create-blob [this store-key env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (RedisBlob. this store-key (atom {}) (atom nil)))))
  (-delete-blob [_ store-key env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (delete client store-key))))
  (-blob-exists? [_ store-key env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (exists? client store-key))))
  (-copy [_ from to env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (copy client from to))))
  (-atomic-move [_ from to env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (copy client from to)
                 (delete client from))))
  (-migratable [_ _key _store-key env]
    (if (:sync? env) nil (go-try- nil)))
  (-migrate [_ _migration-key _key-vec _serializer _read-handlers _write-handlers env]
    (if (:sync? env) nil (go-try- nil)))
  (-handle-foreign-key [_ _migration-key _serializer _read-handlers _write-handlers env]
    (if (:sync? env) nil (go-try- nil)))
  (-create-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                ;; not needed (setup externally)
                (go-try- nil)))
  (-sync-store [_ env]
    (if (:sync? env) nil (go-try- nil)))
  (-delete-store [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (info "Deleting the store is done by deleting all keys.")
                 (doseq [key (list-objects client)]
                   (delete client key)))))
  (-keys [_ env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (remove #{".konserve-store-metadata"} (list-objects client)))))

  PMultiWriteBackingStore
  (-multi-write-blobs
    [_ store-key-values env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (try
                   ;; Execute all writes in a single Redis transaction
                   (let [commands (for [[store-key data] store-key-values
                                        :let [{:keys [header meta value]} data
                                              baos (ByteArrayOutputStream. output-stream-buffer-size)]]
                                    (do
                                      (.write baos header)
                                      (.write baos meta)
                                      (.write baos value)
                                      (let [bytes (.toByteArray baos)]
                                        (.close baos)
                                        [store-key bytes])))

                         ;; Execute the Redis MULTI/EXEC transaction
                         _ (wcar client
                                 (car/multi)
                                 (doseq [[store-key bytes] commands]
                                   (car/set store-key bytes))
                                 (car/exec))

                         ;; If we get here, all writes succeeded
                         ;; Create a result map with all keys mapping to true
                         results (into {} (map (fn [[store-key _]] [store-key true]) store-key-values))]

                     results)

                   ;; Handle any transaction errors
                   (catch Exception e
                     (warn "Redis transaction failed:" (.getMessage e))
                     (throw (ex-info "Redis transaction failed"
                                     {:type :not-supported
                                      :reason "Transaction failed"
                                      :cause e})))))))

  (-multi-delete-blobs [_ store-keys env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (if (empty? store-keys)
                   {}
                   (let [;; Check which keys exist before deleting
                         values (mget-objects client store-keys)
                         existing-keys (into #{}
                                             (keep (fn [[k v]] (when v k))
                                                   (map vector store-keys values)))
                         ;; Delete all keys in one call
                         _ (when (seq existing-keys)
                             (mdelete client (vec existing-keys)))]
                     ;; Return map showing which keys existed
                     (reduce (fn [acc k]
                               (assoc acc k (contains? existing-keys k)))
                             {}
                             store-keys))))))

  PMultiReadBackingStore
  (-multi-read-blobs [this store-keys env]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try-
                 (if (empty? store-keys)
                   {}
                   (let [;; MGET returns values in same order as keys
                         values (mget-objects client store-keys)]
                     ;; Build sparse map - only include keys with non-nil values
                     (reduce (fn [acc [store-key value]]
                               (if value
                                 ;; Create RedisBlob with pre-populated fetched-object (eager loading)
                                 (let [blob (RedisBlob. this store-key (atom {}) (atom value))]
                                   (assoc acc store-key blob))
                                 acc))
                             {}
                             (map vector store-keys values))))))))

(defn connect-store [redis-spec & {:keys [opts]
                                   :as params}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RedisStore. (redis-client redis-spec))
        config (merge {:opts               complete-opts
                       :config             {:sync-blob? true
                                            :in-place? true
                                            :no-backup? true
                                            :lock-blob? true}
                       :default-serializer :FressianSerializer
                       :buffer-size        (* 1024 1024)}
                      (dissoc params :opts :config))]
    (connect-default-store backing config)))

(defn release
  "Must be called after work on database has finished in order to close connection"
  [store env]
  (when-let [pool (-> store :backing :client :pool)]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (.close pool)))))

(defn delete-store [redis-spec & {:keys [opts]}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RedisStore. (redis-client redis-spec))]
    (-delete-store backing complete-opts)))

(comment

  (require '[konserve.core :as k])

  (def redis-spec {:uri "redis://localhost:6379/"})

  (def test-client (redis-client redis-spec))

  (delete-store redis-spec :opts {:sync? true})

  (def store (connect-store redis-spec :opts {:sync? true}))

  (time (k/assoc-in store ["foo"] {:foo "baz"} {:sync? true}))

  (k/get-in store ["foo"] nil {:sync? true})

  (k/exists? store "foo" {:sync? true})

  (time (k/assoc-in store [:bar] 42 {:sync? true}))

  (k/update-in store [:bar] inc {:sync? true})

  (k/get-in store [:bar] nil {:sync? true})

  (k/dissoc store :bar {:sync? true})

  (k/append store :error-log {:type :horrible} {:sync? true})

  (k/log store :error-log {:sync? true})

  (k/keys store {:sync? true})

  (k/bassoc store :binbar (byte-array (range 10)) {:sync? true})

  (k/bget store :binbar (fn [{:keys [input-stream]}]
                          (map byte (slurp input-stream)))
          {:sync? true})

  ;; Multi-key atomic operations example
  (k/multi-assoc store
                 {:user1 {:name "Alice" :role "admin"}
                  :user2 {:name "Bob" :role "user"}
                  :config {:version "1.0"}}
                 {:sync? true})

  ;; Get the values
  (k/get store :user1 nil {:sync? true})
  (k/get store :user2 nil {:sync? true})
  (k/get store :config nil {:sync? true})

  ;; Clean up
  (k/dissoc store :user1 {:sync? true})
  (k/dissoc store :user2 {:sync? true})
  (k/dissoc store :config {:sync? true})

  (release store {:sync? true}))

(comment

  (require '[konserve.core :as k])
  (require '[clojure.core.async :refer [<!!]])

  (<!! (delete-store redis-spec :opts {:sync? false}))

  (def store (<!! (connect-store redis-spec :opts {:sync? false})))

  (time (<!! (k/assoc-in store ["foo" :bar] {:foo "baz"} {:sync? false})))

  (<!! (k/get-in store ["foo"] nil {:sync? false}))

  (<!! (k/exists? store "foo" {:sync? false}))

  (time (<!! (k/assoc-in store [:bar] 42 {:sync? false})))

  (<!! (k/update-in store [:bar] inc {:sync? false}))
  (<!! (k/get-in store [:bar] nil {:sync? false}))
  (<!! (k/dissoc store :bar {:sync? false}))

  (<!! (k/append store :error-log {:type :horrible} {:sync? false}))
  (<!! (k/log store :error-log {:sync? false}))

  (<!! (k/keys store {:sync? false}))

  (<!! (k/bassoc store :binbar (byte-array (range 10)) {:sync? false}))
  (<!! (k/bget store :binbar (fn [{:keys [input-stream]}]
                               (map byte (slurp input-stream)))
               {:sync? false}))

  ;; Multi-key atomic operations example (async)
  (<!! (k/multi-assoc store
                      {:user1 {:name "Alice" :role "admin"}
                       :user2 {:name "Bob" :role "user"}
                       :config {:version "1.0"}}
                      {:sync? false}))

  ;; Get the values
  (<!! (k/get store :user1 nil {:sync? false}))
  (<!! (k/get store :user2 nil {:sync? false}))
  (<!! (k/get store :config nil {:sync? false}))

  ;; Clean up
  (<!! (k/dissoc store :user1 {:sync? false}))
  (<!! (k/dissoc store :user2 {:sync? false}))
  (<!! (k/dissoc store :config {:sync? false}))

  (<!! (release store {:sync? false})))

;; =============================================================================
;; Multimethod Registration for konserve.store dispatch
;; =============================================================================

;; Marker key to identify konserve store existence
(def ^:const store-marker-key ".konserve-store-metadata")

(defmethod store/-connect-store :redis
  [{:keys [uri pool ssl-fn] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               (let [redis-spec (dissoc config :backend)
                     client (redis-client redis-spec)
                     marker-exists (exists? client store-marker-key)]
                 (when-not marker-exists
                   (throw (ex-info (str "Redis store does not exist at: " uri)
                                   {:uri uri :config config})))
                 (connect-store redis-spec)))))

(defmethod store/-create-store :redis
  [{:keys [uri pool ssl-fn] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               (let [redis-spec (dissoc config :backend)
                     client (redis-client redis-spec)
                     marker-exists (exists? client store-marker-key)]
                 (when marker-exists
                   (throw (ex-info (str "Redis store already exists at: " uri)
                                   {:uri uri :config config})))
                 ;; Create marker key with timestamp
                 (put-object client store-marker-key
                             (.getBytes (str {:created-at (java.time.Instant/now)})
                                        "UTF-8"))
                 (connect-store redis-spec)))))

(defmethod store/-store-exists? :redis
  [{:keys [uri] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               (let [redis-spec (dissoc config :backend)
                     client (redis-client redis-spec)]
                 (exists? client store-marker-key)))))

(defmethod store/-delete-store :redis
  [{:keys [uri] :as config} opts]
  (async+sync (:sync? opts) *default-sync-translation*
              (go-try-
               (let [redis-spec (dissoc config :backend)]
                 (delete-store redis-spec)))))

(defmethod store/-release-store :redis
  [_config store _opts]
  ;; Use sync mode for release (cleanup operations are typically fast)
  (release store {:sync? true}))
