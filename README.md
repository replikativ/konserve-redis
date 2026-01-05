# konserve-redis

A [Redis](https://redis.io/) ([carmine](https://github.com/ptaoussanis/carmine))
backend for [konserve](https://github.com/replikativ/konserve).

## Usage

Add to your dependencies:

[![Clojars Project](http://clojars.org/io.replikativ/konserve-redis/latest-version.svg)](http://clojars.org/io.replikativ/konserve-redis)

### Example

For asynchronous execution take a look at the [konserve example](https://github.com/replikativ/konserve#asynchronous-execution).

``` clojure
(require '[konserve-redis.core]  ;; Registers the :redis backend
         '[konserve.core :as k])

(def redis-config
  {:backend :redis
   :uri "redis://localhost:6379/"
   ;; Connection pools are used by default, use `:pool :none` to disable.
   :pool :none
   ;; Redis is assumed to require SSL, use `:ssl-fn :none` to disable.
   :ssl-fn :none
   :opts {:sync? true}})

;; Create a new store (same as connect for Redis - no creation step)
(def store (k/create-store redis-config))

;; Or connect to existing store
;; (def store (k/connect-store redis-config))

;; Check if store exists
(k/store-exists? redis-config) ;; => true

;; Use the store

(k/assoc-in store ["foo" :bar] {:foo "baz"} {:sync? true})
(k/get-in store ["foo"] nil {:sync? true})
(k/exists? store "foo" {:sync? true})

(k/assoc-in store [:bar] 42 {:sync? true})
(k/update-in store [:bar] inc {:sync? true})
(k/get-in store [:bar] nil {:sync? true})
(k/dissoc store :bar {:sync? true})

(k/append store :error-log {:type :horrible} {:sync? true})
(k/log store :error-log {:sync? true})

(let [ba (byte-array (* 10 1024 1024) (byte 42))]
  (time (k/bassoc store "banana" ba {:sync? true})))

(k/bassoc store :binbar (byte-array (range 10)) {:sync? true})
(k/bget store :binbar (fn [{:keys [input-stream]}]
                               (map byte (slurp input-stream)))
       {:sync? true})

;; Multi-key atomic operations
(k/multi-assoc store
               {:user1 {:name "Alice" :role "admin"}
                :user2 {:name "Bob" :role "user"}
                :config {:version "1.0"}}
               {:sync? true})

;; Clean up
(k/delete-store redis-config)

```

## Multi-key Operations

This backend supports multi-key operations (`multi-assoc`, `multi-get`, `multi-dissoc`), allowing you to read, write, or delete multiple keys efficiently.

**No hard item limits** - operations scale with your Redis server capacity.

``` clojure
;; Write multiple keys atomically (uses MULTI/EXEC transaction)
(k/multi-assoc store {:user1 {:name "Alice"}
                      :user2 {:name "Bob"}}
               {:sync? true})

;; Read multiple keys in one request (uses MGET)
(k/multi-get store [:user1 :user2 :user3] {:sync? true})
;; => {:user1 {:name "Alice"}, :user2 {:name "Bob"}}
;; Note: Returns sparse map - only found keys are included

;; Delete multiple keys atomically (uses DEL)
(k/multi-dissoc store [:user1 :user2] {:sync? true})
;; => {:user1 true, :user2 true}
;; Returns map indicating which keys existed before deletion
```

### Implementation Details

| Operation | Redis Command | Atomicity |
|-----------|---------------|-----------|
| `multi-assoc` | MULTI/EXEC with SET | Atomic transaction |
| `multi-get` | MGET | Single round-trip |
| `multi-dissoc` | DEL | Atomic |

Connection pooling is enabled by default, which is important for concurrent multi-key operations. Use `:pool :none` to disable if needed.

## License

Copyright © 2023-2025 Christian Weilbach, Tom Vaughan

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).
