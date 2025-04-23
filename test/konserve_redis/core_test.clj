(ns konserve-redis.core-test
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.core.async :refer [<!!]]
            [konserve.compliance-test :refer [compliance-test]]
            [konserve-redis.core :refer [connect-store release delete-store redis-client]]))

(def redis-spec {:uri "redis://localhost:6379/"
                 :pool {}   ;; Use default pool for tests
                 :ssl-fn :none  ;; Disable SSL for local testing
                 })

(deftest redis-connection-test
  (testing "Basic Redis connection"
    (let [client (redis-client redis-spec)]
      (is (= "PONG" (taoensso.carmine/wcar client (taoensso.carmine/ping)))))))

(deftest redis-store-connect-test
  (testing "Konserve Redis store connection"
    (let [store (connect-store redis-spec :opts {:sync? true})]
      (is (not (nil? store)))
      (release store {:sync? true}))))

(deftest redis-compliance-sync-test
  (let [redis-spec (assoc redis-spec :bucket "konserve-redis-sync-test")
        _     (delete-store redis-spec :opts {:sync? true})
        store (connect-store redis-spec :opts {:sync? true})]
    (testing "Compliance test with synchronous store"
      (compliance-test store))
    (release store {:sync? true})
    (delete-store redis-spec :opts {:sync? true})))

(deftest redis-compliance-async-test
  (let [redis-spec (assoc redis-spec :bucket "konserve-redis-async-test")
        _     (<!! (delete-store redis-spec :opts {:sync? false}))
        store (<!! (connect-store redis-spec :opts {:sync? false}))]
    (testing "Compliance test with asynchronous store"
      (compliance-test store))
    (<!! (release store {:sync? false}))
    (<!! (delete-store redis-spec :opts {:sync? false}))))
