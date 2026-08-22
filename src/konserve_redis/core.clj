(ns konserve-redis.core
  "Redis based konserve backend."
  (:require [clojure.core.async :refer [go]]
            [konserve.impl.defaults :refer [connect-default-store normalize-store-config]]
            [konserve.impl.storage-layout :refer [PBackingStore PBackingBlob PBackingLock
                                                  PMultiWriteBackingStore PMultiReadBackingStore
                                                  PReadMissSafe store-key-not-found-ex
                                                  -delete-store header-size]]
            [konserve.protocols :refer [PConditionalWrite PSelfConditionalWrite]]
            [konserve.utils :refer [async+sync *default-sync-translation*]]
            [konserve.store :as store]
            [superv.async :refer [go-try-]]
            [replikativ.logging :as log]
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

(def ^:private cas-script
  "Compare-and-set on a whole blob, evaluated by Redis.

   `EVAL` runs atomically — Redis executes a script to completion before serving
   anything else — so the comparison and the write are one step against every
   client, which is what makes this backing's guarantee `:global`. It is also one
   round trip, where WATCH/MULTI would need three and connection affinity that
   `wcar` does not give us across konserve's separate read and write calls.

   The comparison is on the EXACT bytes we read, not on a digest of them. A digest
   would keep the payload small (`redis.sha1hex` is available), but a collision
   here is a stale write silently passing its fence, and this is not the place to
   trade correctness for bandwidth. Fencing is for mutable pointers, of which a
   store has a handful.

   ARGV[1] is the marker for `must not exist`; anything else is the expected value."
  "local cur = redis.call('GET', KEYS[1])
   if ARGV[1] == ARGV[3] then
     if cur then return 0 end
   elseif cur ~= ARGV[1] then
     return 0
   end
   redis.call('SET', KEYS[1], ARGV[2])
   return 1")

(def ^:private absent-marker
  "Sentinel for ARGV[1] meaning THE KEY MUST NOT EXIST. A byte string no stored
   blob can equal: konserve blobs always begin with a header."
  "konserve-redis/absent")

(defn put-object-conditional
  "SET `key` to `bytes` only if it currently holds `expected` — or, when `expected`
   is `::absent`, only if it holds nothing. True on success, false on conflict."
  [client ^String key ^bytes bytes expected]
  (let [argv (if (= ::absent expected)
               [absent-marker bytes absent-marker]
               [expected bytes absent-marker])]
    (= 1 (wcar client (apply car/eval cas-script 1 key argv)))))

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
                               baos (ByteArrayOutputStream. output-stream-buffer-size)
                               expected-revision (:expected-revision env)]
                           (if (and header meta value)
                             (do
                               (.write baos header)
                               (.write baos meta)
                               (.write baos value)
                               (let [bytes (.toByteArray baos)]
                                 (if expected-revision
                                   ;; FENCED. konserve has already compared the
                                   ;; revision it read against the caller's; this
                                   ;; closes the window BETWEEN that read and this
                                   ;; write, which is the half no counter can do.
                                   ;; Together they are the compare-and-set.
                                   ;;
                                   ;; What we read is remembered by `-read-header`
                                   ;; and looked up here, because `-sync` runs on a
                                   ;; DIFFERENT blob record than the read did —
                                   ;; `update-blob` creates its own. No entry means
                                   ;; no read happened, which for a fenced write is
                                   ;; create-if-absent.
                                   (let [cache (:read-cache store)
                                         expected (get @cache key ::absent)]
                                     (try
                                       (when-not (put-object-conditional (:client store) key bytes expected)
                                         (throw (ex-info (str "Conditional write rejected: the stored value is not "
                                                              "the one this write was derived from.")
                                                         {:type :konserve/revision-mismatch
                                                          :key key
                                                          :expected expected-revision})))
                                       (finally
                                         ;; Whatever happened, this read is spent.
                                         (swap! cache dissoc key))))
                                   (put-object (:client store) key bytes)))
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
                 ;; PReadMissSafe: GET returns nil for an absent key. Signal not-found;
                 ;; io-operation's read-first path converts it to the caller's :not-found.
                 (when (nil? @fetched-object)
                   (throw (store-key-not-found-ex key)))
                 ;; Remember it for a fenced `-sync`, and ONLY for one. The read
                 ;; that precedes a conditional write carries `:expected-revision`
                 ;; in its env, so we can tell — caching on every read would hold
                 ;; the last-read bytes of every key a store ever touched, which
                 ;; for a datahike-shaped workload is the whole index.
                 (when (:expected-revision env)
                   (swap! (:read-cache store) assoc key @fetched-object))
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

(defrecord RedisStore [client read-cache]
  ;; Redis evaluates the comparison — see `cas-script` — so konserve adds no
  ;; mechanism of its own: no sidecar blob, no lock it would take. Declared rather
  ;; than inferred from the domain, since reach and mechanism are separate
  ;; questions.
  PSelfConditionalWrite

  PConditionalWrite
  ;; `:global`. EVAL is atomic against every client of this Redis, not merely
  ;; those sharing a filesystem or a heap.
  (-conditional-write-domain [_] :global)

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
                 (log/info :konserve.redis/delete-store "Deleting the store by deleting all keys.")
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
                     (log/warn :konserve.redis/transaction-failed {:message (.getMessage e)})
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

;; Redis reads are read-miss-safe: -create-blob only constructs a RedisBlob (no
;; side effect), and -read-header throws store-key-not-found-ex when GET returns
;; nil. So io-operation skips the -blob-exists? (EXISTS) probe — a read is one GET,
;; and update-in/assoc-in/bassoc drop their probe too.
(extend-type RedisStore
  PReadMissSafe)

(defn connect-store [redis-spec & {:keys [opts]
                                   :as params}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RedisStore. (redis-client redis-spec) (atom {}))
        config (merge {:opts               complete-opts
                       :config             {:sync-blob? true
                                            :in-place? true
                                            :no-backup? true
                                            :lock-blob? true}
                       :buffer-size        (* 1024 1024)}
                      (dissoc params :opts :config))
        ;; `:config` IS forwarded now. It used to be dissoc'd, so the literal
        ;; default always won and a caller could not configure compression or
        ;; encryption at all -- the blob header carried a 0 whatever they
        ;; asked for. Merged onto the defaults rather than replacing them, so
        ;; a partial `:config` keeps the rest.
        ;;
        ;; Normalised BEFORE our own serializer default is filled: emitting
        ;; `:default-serializer` would trip konserve's deprecation warning on
        ;; every connect whatever the caller passed, and filling first would
        ;; let it occupy the slot and silently drop a caller's older spelling.
        config (-> config
                   (assoc :config (merge {:sync-blob? true
                                          :in-place? true
                                          :no-backup? true
                                          :lock-blob? true}
                                         (:config params)))
                   normalize-store-config
                   (update-in [:config :encoding]
                              #(merge {:serializer :FressianSerializer} %)))]
    (connect-default-store backing config)))

(defn release
  "Must be called after work on database has finished in order to close connection"
  [store env]
  (when-let [pool (-> store :backing :client :pool)]
    (async+sync (:sync? env) *default-sync-translation*
                (go-try- (.close pool)))))

(defn delete-store [redis-spec & {:keys [opts]}]
  (let [complete-opts (merge {:sync? true} opts)
        backing (RedisStore. (redis-client redis-spec) (atom {}))]
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
  [_config store opts]
  ;; Release respecting caller's sync mode
  (release store opts))
