(ns konserve-redis.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :refer [<!!]]
            [konserve.compliance-test :refer [compliance-test
                                              conditional-write-compliance-test]]
            [konserve.core :as k]
            [konserve.impl.storage-layout :as sl]
            [konserve-redis.core :as redis]
            [konserve.store :as store])
  (:import [java.util UUID]))

;; Local Redis configuration (docker-compose up -d)
(def redis-spec {:uri "redis://localhost:6379/"
                 :pool {}   ;; Use default pool for tests
                 :ssl-fn :none  ;; Disable SSL for local testing
                 :id (UUID/randomUUID)  ;; Unique store identifier
                 })

(deftest redis-connection-test
  (testing "Basic Redis connection"
    (let [client (redis/redis-client redis-spec)]
      (is (= "PONG" (taoensso.carmine/wcar client (taoensso.carmine/ping)))))))

(deftest redis-store-connect-test
  (testing "Konserve Redis store connection"
    (let [store (redis/connect-store redis-spec :opts {:sync? true})]
      (is (not (nil? store)))
      (redis/release store {:sync? true}))))

(deftest redis-compliance-sync-test
  (let [spec (assoc redis-spec :backend :redis)]
    ;; Clean up first
    (try (store/delete-store spec {:sync? true}) (catch Exception _))

    ;; Create and test
    (let [st (store/create-store spec {:sync? true})]
      (testing "Compliance test with synchronous store"
        (compliance-test st))
      (redis/release st {:sync? true})
      (store/delete-store spec {:sync? true}))))

(deftest redis-conditional-write-test
  (testing "the `:expected-revision` contract against a real Redis.

            konserve's shared contract, not a restatement of it here — a backend
            that restates it drifts. A store without the capability passes the
            same suite by refusing, so this is the same call either way."
    (let [spec (assoc redis-spec :backend :redis :id (UUID/randomUUID))]
      (try (store/delete-store spec {:sync? true}) (catch Exception _))
      (let [st (store/create-store spec {:sync? true})]
        (try
          (is (= :global (k/conditional-write-domain st))
              "EVAL is atomic against every client of this Redis, not just this host")
          (conditional-write-compliance-test st)
          (finally
            (redis/release st {:sync? true})
            (store/delete-store spec {:sync? true})))))))

(deftest redis-concurrent-fenced-counter-test
  (testing "concurrent increments converge when the caller fences and retries, and
            no update is lost. Five threads, ten increments each, five store
            instances against one Redis — the case an unfenced read-modify-write
            gets wrong, since konserve's blob lock here is a no-op."
    (let [spec (assoc redis-spec :backend :redis :id (UUID/randomUUID))]
      (try (store/delete-store spec {:sync? true}) (catch Exception _))
      (let [init (store/create-store spec {:sync? true})
            _ (k/assoc-in init [:counter] 0 {:sync? true})
            _ (redis/release init {:sync? true})
            threads 5 per-thread 10
            expected (* threads per-thread)
            conflicts (atom 0)
            unexpected (atom [])
            fs (doall
                (for [_ (range threads)]
                  (future
                    (let [st (store/connect-store spec {:sync? true})]
                      (try
                        (dotimes [_ per-thread]
                          (loop [tries 0]
                            (let [rev (k/revision st :counter {:sync? true})
                                  r (try (k/update-in st [:counter] (fnil inc 0)
                                                      {:sync? true :expected-revision rev})
                                         ::ok
                                         (catch Exception e (or (:type (ex-data e)) ::other)))]
                              (cond
                                (= ::ok r) :done
                                (= :konserve/revision-mismatch r)
                                (do (swap! conflicts inc)
                                    (when (< tries 500) (recur (inc tries))))
                                :else (swap! unexpected conj r)))))
                        (finally (redis/release st {:sync? true})))))))]
        (doseq [f fs] @f)
        (let [fin (store/connect-store spec {:sync? true})]
          (is (empty? @unexpected) (str "unexpected failures: " (pr-str @unexpected)))
          (is (= expected (k/get-in fin [:counter] nil {:sync? true}))
              "every increment must survive")
          (is (pos? @conflicts)
              (str "the threads must actually have contended (" @conflicts "); "
                   "a run with none shows the fence held but not that it was needed"))
          (redis/release fin {:sync? true}))
        (store/delete-store spec {:sync? true})))))

(deftest redis-compliance-async-test
  (let [spec (assoc redis-spec :backend :redis)]
    ;; Clean up first
    (try (<!! (store/delete-store spec {:sync? false})) (catch Exception _))

    ;; Create and test
    (let [st (<!! (store/create-store spec {:sync? false}))]
      (testing "Compliance test with asynchronous store"
        (compliance-test st))
      (<!! (redis/release st {:sync? false}))
      (<!! (store/delete-store spec {:sync? false})))))

(deftest redis-read-miss-safe-marker-test
  (testing "Redis backing implements PReadMissSafe (io-operation skips the EXISTS probe on reads)"
    (let [store (redis/connect-store redis-spec :opts {:sync? true})]
      (is (satisfies? sl/PReadMissSafe (:backing store)))
      (redis/release store {:sync? true}))))
