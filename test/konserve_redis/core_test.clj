(ns konserve-redis.core-test
  (:require [clojure.test :refer [deftest testing]]
            [clojure.core.async :refer [<!!]]
            [konserve.compliance-test :refer [compliance-test]]
            [konserve-redis.core :refer [connect-store release delete-store]]))

(def redis-spec {:uri "redis://localhost:6379/"})

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
