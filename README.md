# konserve-redis

A [Redis](https://redis.io/) ([carmine](https://github.com/ptaoussanis/carmine))
backend for [konserve](https://github.com/replikativ/konserve).

## Usage

Add to your dependencies:

[![Clojars Project](http://clojars.org/io.replikativ/konserve-redis/latest-version.svg)](http://clojars.org/io.replikativ/konserve-redis)

### Example

For asynchronous execution take a look at the [konserve example](https://github.com/replikativ/konserve#asynchronous-execution).

``` clojure
(require '[konserve-redis.core :refer [connect-store]]
         '[konserve.core :as k])

(def redis-spec
  {:uri "redis://localhost:6379/"
   ;; Connection pools are used by default, use `:pool :none` to disable.
   :pool :none
   ;; Redis is assumed to require SSL, use `:ssl-fn :none` to disable.
   :ssl-fn :none})

(def store (connect-store redis-spec :opts {:sync? true}))

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

```

## Commercial support

We are happy to provide commercial support with
[lambdaforge](https://lambdaforge.io). If you are interested in a particular
feature, please let us know.

## License

Copyright © 2023 Christian Weilbach

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).
