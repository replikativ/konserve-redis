(ns konserve-redis.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :refer [<!!]]
            [konserve.compliance-test :refer [compliance-test]]
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
