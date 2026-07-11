# Changelog

All notable, user-visible changes to konserve-redis are documented here.

## Unreleased

### Added
- **Read-miss-safe reads (one GET, no EXISTS probe).** The Redis backing implements
  konserve's `PReadMissSafe` and `-read-header` throws `store-key-not-found-ex` when
  GET returns nil. On a konserve that supports the marker the redundant
  `-blob-exists?` (EXISTS) probe is dropped, so a read is one GET, and read-modify-write
  ops (`update-in` / `assoc-in` / `bassoc`) skip it too. Requires konserve `0.9.354`+.

### Changed
- konserve `0.9.342` → `0.9.354`.
- CI now runs the compliance suite (sync + async) against a Redis service container,
  plus a smoke load-check that catches a stale konserve pin; the release is gated on both.
