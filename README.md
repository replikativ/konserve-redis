# konserve-redis

A [Redis](https://redis.io/) ([carmine](https://github.com/ptaoussanis/carmine))
backend for [konserve](https://github.com/replikativ/konserve).

## Usage

Add to your dependencies:

[![Clojars Project](http://clojars.org/io.replikativ/konserve-redis/latest-version.svg)](http://clojars.org/io.replikativ/konserve-redis)

### Configuration

``` clojure
(require '[konserve-redis.core]  ;; Registers the :redis backend
         '[konserve.core :as k])

(def config
  {:backend :redis
   :uri "redis://localhost:6379/"
   :id #uuid "550e8400-e29b-41d4-a716-446655440000"
   ;; Optional:
   :pool {:min-idle 2 :max-idle 8 :max-total 8}  ;; Connection pool config
   :ssl-fn :none})                               ;; Disable SSL for local

(def store (k/create-store config {:sync? true}))
```

For API usage (assoc-in, get-in, delete-store, etc.), see the [konserve documentation](https://github.com/replikativ/konserve).

## Implementation Details

### Multi-key Operations

This backend supports multi-key operations (`multi-assoc`, `multi-get`, `multi-dissoc`).

**No hard item limits** - operations scale with your Redis server capacity.

| Operation | Redis Command | Atomicity |
|-----------|---------------|-----------|
| `multi-assoc` | MULTI/EXEC with SET | Atomic transaction |
| `multi-get` | MGET | Single round-trip |
| `multi-dissoc` | DEL | Atomic |

Connection pooling is enabled by default, which is important for concurrent multi-key operations. Use `:pool {:min-idle 0 :max-idle 1 :max-total 1}` for single connection or `:pool :none` to disable.

## License

Copyright © 2023-2026 Christian Weilbach, Tom Vaughan

Licensed under Eclipse Public License (see [LICENSE](LICENSE)).
