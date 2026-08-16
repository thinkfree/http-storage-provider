# Secure a production Provider

The request JWT proves that a configured Office adapter created an unmodified
request within a short interval. It does not replace tenant authorization,
storage access control, network policy, or safe file handling.

## Keep one trust boundary

Verify the complete request before reading or changing document storage:

1. Resolve a secret from the adapter header through server-side configuration.
2. Verify `HS256`, JWT type, issuer, audience, issue time, expiry, and `jti`.
3. Compare adapter, method, raw encoded path, content type, body length, and body
   SHA-256 with the actual HTTP request.
4. Atomically consume `jti` until its expiry.
5. Authorize the adapter, tenant, document path, and operation.
6. Access the backing storage only after those checks pass.

Return the same generic `401 Unauthorized` response for unknown adapters,
invalid signatures, expired requests, mismatched claims, and replay. Detailed
authentication errors help an attacker distinguish valid configuration.

## Protect the shared secret

- Generate at least 32 random bytes for every adapter connection.
- Store it in the Office secret field and the Provider's secret manager.
- Never put it in a URL, container image, repository, client metadata, log, or
  error response.
- Restrict reveal and rotation to authorized operators. A rotation requires a
  coordinated Provider and Office configuration change.
- Use TLS whenever the request leaves a trusted private network. The JWT signs
  integrity but does not encrypt paths, metadata, or document bytes.

## Contain document paths

Decode each percent-encoded segment once. Reject an invalid encoding, empty
segment, `.`, `..`, separators inside a segment, control characters, absolute
path, and a path that leaves the assigned root. Recheck containment immediately
before a state-changing storage call.

The examples reject symbolic links and reserve `.tfo-http-storage-state` for
Provider state. If a production store supports aliases or links, enforce its
equivalent tenant-root containment rather than copying filesystem assumptions.

## Stream and stage complete files

Office sends `put` with a fixed `Content-Length`. Both examples stream the body
to a restricted staging file while calculating SHA-256, verify the signed
request, and then move the staged file to the destination. They delete a staged
file after any failed request.

Apply the 300 MiB protocol hard gate, or a smaller deployment limit, before and
during staging. Keep staging on the same
filesystem as the destination when relying on atomic rename. Monitor free
space, stale staging files, failed cleanup, and write latency. Do not replace
the streaming loop with an unbounded byte array.

For responses, serialize only INFO/LIST metadata up to 5 MiB in memory and set
its exact UTF-8 `Content-Length`. Resolve a document's original size before GET
and reject files above 300 MiB before sending headers. Stream accepted files
with `application/octet-stream`. Do not gzip or use chunked transfer for INFO,
LIST, or GET; GET bodies must never be assembled into one byte array.

## Coordinate locks and replay across replicas

The example state uses atomic files below the local storage root. A production
Provider with more than one process or replica needs shared state with atomic
create-if-absent behavior and expiry:

- Retain every consumed `jti` until JWT expiry.
- Associate a document lock with its owner.
- Make a repeated lock by the same owner idempotent.
- Reject a different owner with `409`.
- Make unlock idempotent after the correct owner has released the lock.
- Expire or reconcile abandoned locks according to your Office session policy.

A process-local map is insufficient for multiple replicas and loses state on
restart.

## Limit the network surface

- Expose only the Provider routes and an intentionally minimal health route.
- Restrict ingress to approved Office runtimes where network architecture
  allows it.
- Reject query strings, redirects, cookies, chunked PUT, and unknown methods.
- Set connection, body, storage, and response timeouts at the server and proxy.
- Rate-limit repeated authentication failures without blocking valid Office
  document saves.
- Keep internal paths, upstream responses, document content, JWTs, and secrets
  out of logs.

## Review the example before production

The local-directory servers are complete protocol examples, not a claim that a
single host filesystem meets your durability, backup, disaster recovery,
retention, audit, malware scanning, or multi-tenant isolation requirements.
Document those requirements for the backing store you select.
