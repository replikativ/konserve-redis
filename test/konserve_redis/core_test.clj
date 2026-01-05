(ns konserve-redis.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :refer [<!!]]
            [konserve.compliance-test :refer [compliance-test]]
            [konserve-redis.core :as redis]
            [konserve.store :as store]))

;; Local Redis configuration (docker-compose up -d)
(def redis-spec {:uri "redis://localhost:6379/"
                 :pool {}   ;; Use default pool for tests
                 :ssl-fn :none  ;; Disable SSL for local testing
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
  (let [spec (assoc redis-spec :backend :redis :opts {:sync? true})]
    ;; Clean up first
    (try (store/delete-store spec) (catch Exception _))

    ;; Create and test
    (let [st (store/create-store spec)]
      (testing "Compliance test with synchronous store"
        (compliance-test st))
      (redis/release st {:sync? true})
      (store/delete-store spec))))

(deftest redis-compliance-async-test
  (let [spec (assoc redis-spec :backend :redis :opts {:sync? false})]
    ;; Clean up first
    (try (<!! (store/delete-store spec)) (catch Exception _))

    ;; Create and test
    (let [st (<!! (store/create-store spec))]
      (testing "Compliance test with asynchronous store"
        (compliance-test st))
      (<!! (redis/release st {:sync? false}))
      (<!! (store/delete-store spec)))))
