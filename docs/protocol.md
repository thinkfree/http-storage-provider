# TFO HTTP Storage Protocol v1 reference

The packaged Self-hosted Office HTTP Storage adapter calls this Provider
contract. It is a storage protocol between Office and a customer-operated
Provider, not a general-purpose HTTP proxy or a Thinkfree-hosted public API.

For Provider base URL `{BASE_URL}` and document path `{DOCUMENT_PATH}`, Office
calls:

```text
{BASE_URL}/tfo-http-storage/v1/{ENCODED_DOCUMENT_PATH}/{OPERATION}
```

Each UTF-8 path segment is percent-encoded independently. The Provider root has
an empty document path, so its list route is:

```text
{BASE_URL}/tfo-http-storage/v1/list
```

The base URL must use HTTP or HTTPS and cannot contain credentials, a query,
fragment, or dot segment. The protocol does not use query strings, cookies,
redirects, arbitrary forwarding headers, or compatibility routes.

## Authenticate every request

Every operation includes this authentication header:

```http
X-TFO-Storage-Request-JWT: eyJhbGciOiJIUzI1NiIsInR5cCI6InRmby1zdG9yYWdlLXJlcXVlc3Qrand0In0...
```

The JWT uses `HS256` and header `typ=tfo-storage-request+jwt`. Before parsing,
reject tokens above 8,192 UTF-8 bytes. Parse unverified `request.adapter` only
as a lookup hint for an existing server-configured adapter secret. Reject a
missing, non-string, or unknown adapter. These examples register one adapter.
Never accept a secret, key URL, or new adapter registration from caller input.

Verify the original JWT with the selected key, not a token reconstructed from
decoded JSON. Verify all header/claim and actual-request bindings below, then
atomically consume `jti` before exposing the verified `request`. The current
Office adapter does not send `X-TFO-Storage-Adapter`. Providers ignore it if an
older caller sends it; a mismatch neither selects a key nor overrides the JWT.

```json
{
  "iss": "thinkfree-office",
  "aud": "tfo-http-storage-provider",
  "iat": 1786795200,
  "exp": 1786795260,
  "jti": "26bc9b5e-9dab-4a78-95d8-b1ae34a5d9eb",
  "request": {
    "adapter": "customer-storage-a",
    "method": "PUT",
    "path": "/office/tfo-http-storage/v1/contracts/sample.docx/put",
    "content_type": "application/octet-stream",
    "content_length": 48231,
    "content_sha256": "lowercase-hex-sha256",
    "office_connection_id": "optional-office-runtime-id",
    "arguments": {"save_type": "save"},
    "client_metadata": {"customer_context": "caller-defined-value"}
  }
}
```

Validate the signed values against the actual request before storage access.

| Field | Location | Type | Required | Meaning / allowed values |
| --- | --- | --- | --- | --- |
| `alg` | JWT header | string | Yes | Exact value `HS256`. |
| `typ` | JWT header | string | Yes | Exact value `tfo-storage-request+jwt`. |
| `iss` | JWT claim | string | Yes | Exact value `thinkfree-office`. |
| `aud` | JWT claim | string or one-item array | Yes | The only audience is `tfo-http-storage-provider`. |
| `iat` | JWT claim | integer | Yes | Issued-at Unix time; cannot be in the future. |
| `exp` | JWT claim | integer | Yes | Expiry Unix time; must be after `iat` and at most 60 seconds later. |
| `jti` | JWT claim | string | Yes | Unique request ID. Atomically reject reuse until `exp`. |
| `request.adapter` | signed request | string | Yes | Names an existing configured Provider connection and remains bound to the selected key after verification. |
| `request.method` | signed request | string | Yes | Matches the actual uppercase HTTP method. |
| `request.path` | signed request | string | Yes | Matches the actual raw encoded path, including the Provider base path. |
| `request.content_length` | signed request | integer | Yes | Exact body byte length; `0` when no body exists. |
| `request.content_sha256` | signed request | string | Yes | Lowercase SHA-256 of the exact body. Use the empty-body digest when no body exists. |
| `request.content_type` | signed request | string | When sent | Matches the actual `Content-Type` exactly. |
| `request.office_connection_id` | signed request | string | No | Opaque Office runtime context. It is not authorization. |
| `request.arguments` | signed request | object | No | Operation context such as `save_type`. Unknown values are not authorization. |
| `request.client_metadata` | signed request | object | No | Caller context subject to the structure limits below. Its signature protects transit integrity, not customer identity or authority. |

The complete JWT cannot exceed 8,192 UTF-8 bytes. The adapter creates a new
`jti` for every request. A Provider must retain used IDs until expiry in an
atomic store shared by every replica.

## Pass customer context

Pass `__tfo_adapter_http_storage_client_metadata` as a JSON **string** in the
Office `/open` parameters, or in the signed open ticket's `command.params`.
For example, this is the relevant fragment of a ticket command, not a complete
ticket or an example credential:

```json
{
  "params": {
    "__tfo_adapter_http_storage_client_metadata": "{\"sessionId\":\"customer-issued-document-session-reference\"}"
  }
}
```

Office parses and preserves this context per connection, and includes it as a
JSON **object** in each subsequent storage request JWT:

```json
{
  "request": {
    "client_metadata": {
      "sessionId": "customer-issued-document-session-reference"
    }
  }
}
```

`sessionId` is an illustrative customer-defined field, not an Office identity
claim or an authentication service provided by these examples. The Provider
uses only the metadata returned by its verifier, then checks the referenced
session's expiry, revocation, tenant/user, requested document, and operation in
its own trusted system. Never authorize from an unverified JWT payload or from
the metadata's asserted user, tenant, or permissions alone.

The two size boundaries are distinct:

| Boundary | Limit |
| --- | --- |
| Original metadata JSON string received by Office at `/open` or in ticket parameters | 4,096 UTF-8 bytes, checked by Office before parsing. |
| Complete incoming storage request JWT | 8,192 UTF-8 bytes, checked by the Provider before parsing. |
| Parsed metadata | Object root; maximum depth 8, counting the root as 0 and every object value/array element as one additional level. |
| Object keys at every depth | Nonblank and at most 64 UTF-16 code units. |
| String values, including inside arrays | At most 512 UTF-16 code units. |

The string limits use Java `String.length()`, not Unicode code-point counts:
an emoji outside the basic multilingual plane occupies two units. Blank means
Java `String.isBlank()` / `Character.isWhitespace`; for example, an all-space
key is invalid but a nonbreaking space (U+00A0) is not Java whitespace. Do not
substitute JavaScript `trim()` or Python `isspace()` for this exact rule.

Do not impose another 4,096-byte cap on the metadata's raw span in the JWT or
on a reserialized object. Number formatting can expand a valid input: an
`items` array of 700 copies of `1e-7` is 3,511 bytes on input, but 4,911 bytes
when serialized as `1.0E-7`. This is valid if the complete JWT stays within
8,192 bytes. The original input string cannot be recovered from the JWT object.

Use a short-lived, narrowly scoped document-session reference when needed.
Do not forward browser cookies, long-lived credentials, or shared secrets in
metadata. It is stored with the Office connection and appears in signed,
base64url-encoded JWTs, not encrypted tokens. A query-based `/open` delivery
also exposes it to URL/history/logging surfaces. Use the supported signed
ticket flow and redact sensitive context from diagnostics; signing is not
encryption. See [customer authorization](security.md#authorize-customer-access).

## Check access before opening and on every operation

For system adapters, Office's document-open flow requires `info` after a
successful internal `start`, and checks that the document exists and is
readable before proceeding to the editor. HTTP Storage customers implement
HTTP `info`, `get`, `put`, and the other Provider operations, not Java `start`.
The Provider must validate the customer session and read permission in `info`.
Recheck current permissions on `get`, `put`, and every later operation; an
earlier successful open does not grant permanent authorization. Return a safe
denial or missing-file response before storage access when checks fail.

## Implement the endpoint catalog

| Role | Required | Method | Path suffix | Request | Success response |
| --- | --- | --- | --- | --- | --- |
| Read metadata | Yes | `GET` | `/{path}/info` | No body | JSON entry with fixed `Content-Length`; a missing item is `404`. |
| List direct children | No | `GET` | `/{path}/list` | No body | JSON object with `entries` and fixed `Content-Length`. |
| Download | Yes | `GET` | `/{path}/get` | No body | Raw bytes with fixed `Content-Length`. |
| Save | No | `PUT` | `/{path}/put` | Raw bytes and fixed `Content-Length` | Required JSON object with `docId` and fixed `Content-Length`. |
| Lock | No, paired with unlock | `POST` | `/{path}/lock` | `{"owner":"..."}` | Any `2xx`. |
| Unlock | No, paired with lock | `POST` | `/{path}/unlock` | `{"owner":"..."}` | Any `2xx`. |
| Create directory | No | `POST` | `/{parent}/mkdir` | `{"name":"..."}` | Any `2xx`. |
| Rename | No | `POST` | `/{path}/rename` | `{"name":"..."}` | Any `2xx`. |
| Delete | No | `DELETE` | `/{path}/delete` | No body | Any `2xx`. |

`PUT` uses `application/octet-stream`. The four JSON operations use
`application/json` and exactly the documented one-field object. The packaged
adapter sends a fixed `Content-Length`; chunked request transfer is not part of
the protocol.

### Frame successful responses with a fixed length

Every successful `info`, `list`, `get`, and `put` response must include exactly one
decimal `Content-Length` whose value is the exact number of response-body
bytes. Do not send `Transfer-Encoding: chunked` or `Content-Encoding`; Office
does not buffer an unknown or compressed body to discover its final size.

`info`, `list`, and `put` use `application/json` UTF-8 bodies and are each limited by the
packaged adapter to 5 MiB. Serialize the bounded JSON once, calculate its
UTF-8 byte length, set `Content-Length`, and then send those same bytes. `get`
uses `application/octet-stream`. Determine the stored object's original size
before writing headers, then stream exactly that many bytes. File metadata,
GET response length, and PUT request length have a protocol hard gate of
300 MiB (314,572,800 bytes). A Provider may configure a smaller tenant or
deployment limit, but must not advertise or transfer a larger document.

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 184
Cache-Control: no-store
```

A missing, duplicate, negative, non-decimal, or oversized length; chunked or
compressed transfer; wrong media type; early EOF; and a body that exceeds its
declared length are Provider errors. Office fails only that operation, closes
the upstream response, and keeps the adapter service available for later
requests. Error responses and the authenticated `501` capability response
remain bounded, but only successful INFO/LIST/GET/PUT responses use this mandatory
fixed-read contract.

### Return the saved document identity

After saving, return a `2xx` response with UTF-8 `application/json` and a fixed
`Content-Length`. The required body contains exactly one field:

```json
{"docId":"saved-document-id"}
```

`docId` is a 1–1,024 character identifier, beginning with an ASCII letter or digit
and containing only ASCII letters, digits, `.`, `_`, `:`, and `-`. The values
`true` and `false` are reserved, regardless of case. Do not trim or rewrite IDs.
Return the identity of the saved destination for every save type. Within an
adapter, the same destination keeps the same ID when contents change, and
different destinations have different IDs. Do not return a revision or the
original session's document ID for a save to another destination.

Empty bodies, `204`, plain text IDs, XML, JSON scalars (including `true`), missing
or extra fields, duplicate fields, and trailing JSON values or text are rejected.
See the [PUT response schema](../schemas/v1/put-response.schema.json).
The reference servers use the lowercase SHA-256 of the decoded root-relative
UTF-8 path as `docId`; production Providers can use their own stable IDs that
satisfy the same format. Metadata revisions remain available through `info`.

### Declare that an operation is not implemented

A Provider may intentionally omit one or more operations. It must parse the
request body when applicable and authenticate the complete signed request
before returning this exact capability response. This ordering prevents an
unauthenticated caller from probing Provider capabilities. The example is for
`list`:

```http
HTTP/1.1 501 Not Implemented
Content-Type: application/json
```

```json
{
  "code": "LIST_NOT_SUPPORTED"
}
```

Use the exact current operation name followed by `_NOT_SUPPORTED`:

| Operation | Code |
| --- | --- |
| `list` | `LIST_NOT_SUPPORTED` |
| `put` | `PUT_NOT_SUPPORTED` |
| `lock` | `LOCK_NOT_SUPPORTED` |
| `unlock` | `UNLOCK_NOT_SUPPORTED` |
| `mkdir` | `MKDIR_NOT_SUPPORTED` |
| `rename` | `RENAME_NOT_SUPPORTED` |
| `delete` | `DELETE_NOT_SUPPORTED` |

`info` and `get` are mandatory because Office needs them to identify and open
a document. A Provider cannot declare either operation unsupported; any `501`
from those routes remains an operation failure regardless of its body.

`lock` and `unlock` form one optional capability. Implement both or declare
both unsupported. A Provider without locking returns `LOCK_NOT_SUPPORTED` and
`UNLOCK_NOT_SUPPORTED` from the respective authenticated routes; it must not
pretend to acquire a lock. Office treats those exact responses as successful
no-ops so the document can still open and close. This means concurrent writers
use the backing store's last-write/conflict policy. When locking is implemented,
an owner conflict (`409`), authorization failure, or storage outage remains a
real operation failure and must never be converted to a capability response.

Office recognizes an operation as unsupported only for this exact status,
media type, and single-field JSON body whose code matches the requested
operation. A `404`, empty or non-JSON body, extra field, mismatched code,
authentication failure, timeout, or another `501` remains an operation error.
An empty directory supports listing and returns `200` with `{"entries":[]}`.
The exact bodies are published in the
[`operation-not-supported-response` schema](../schemas/v1/operation-not-supported-response.schema.json).

The reference servers remain complete by default. To demonstrate an omitted
operation, set a comma-separated list before startup, for example
`TFO_STORAGE_UNSUPPORTED_OPERATIONS=list,rename`. Declare `lock,unlock`
together if locking is omitted. Each server checks this only
after JWT verification and returns the exact response without accessing the
backing storage. In application code the essential boundary is:

```text
route = parse_and_read_request()
verify_signed_request(route, body)
if configured_as_unsupported(route.operation):
    return 501 application/json {"code":"<OPERATION>_NOT_SUPPORTED"}
execute_storage_operation()
```

## Return metadata JSON

`info` returns one entry. `list` returns direct children only and contains at
most 10,000 entries.

```json
{
  "path": "contracts/sample.docx",
  "name": "sample.docx",
  "type": "file",
  "size": 48231,
  "readable": true,
  "writable": true,
  "locked": false,
  "locker": null,
  "createdAt": "2026-08-15T00:00:00Z",
  "modifiedAt": "2026-08-15T00:10:00Z",
  "revision": "revision-17"
}
```

| Field | Type | Required | Meaning / allowed values |
| --- | --- | --- | --- |
| `path` | string | Yes | Normalized path relative to the Provider root. The root is an empty string. An `info` result must match the requested path. |
| `name` | string | Yes | Final path segment. Use a stable display name for the root. Maximum 255 characters. |
| `type` | string | Yes | `file` or `directory`. |
| `size` | integer | Yes | Non-negative content length in bytes. A directory uses `0`. |
| `readable` | boolean | Yes | Whether Office can read the item. |
| `writable` | boolean | Yes | Whether Office can write the item. |
| `locked` | boolean | Yes | Whether an external lock currently exists. |
| `locker` | string or null | No | Opaque current lock owner. |
| `createdAt` | string or null | No | RFC 3339 timestamp. |
| `modifiedAt` | string or null | No | RFC 3339 timestamp. |
| `revision` | string or null | No | Provider revision or ETag-like value, maximum 1,024 characters. |

Unknown entry fields, duplicate list paths, entries that are not direct
children, invalid timestamps, inconsistent names, and a nonzero directory size
are rejected by the packaged adapter. Use the Draft 2020-12 schemas in
[`schemas/v1`](../schemas/v1) as the machine-readable contract.

## Preserve operation semantics

- `get` resolves the original size before sending headers, publishes that
  exact `Content-Length`, and streams the same number of stored bytes.
- `put` receives the complete assembled Office file. Stage and hash it before
  verification, then replace the target only after authorization and complete
  length validation.
- `lock` is idempotent for the same owner and returns `409` for another owner.
  `unlock` is idempotent when no lock remains and rejects a different owner.
- `mkdir` and `rename` accept one child name. Reject separators, `.`, and `..`.
  Rename stays inside the same parent.
- `delete` refuses the Provider root. This repository rejects a non-empty
  directory rather than deleting recursively.

## Return safe errors

Except for an exact authenticated `<OPERATION>_NOT_SUPPORTED` capability response,
any non-`2xx` response fails the Office operation. `info` uses `404` for a
missing item. These status codes give the administrator an actionable category:

| Status | Use |
| --- | --- |
| `400` | Malformed path, body, content length, or unsupported query. |
| `401` | Unknown adapter, invalid signature, claim mismatch, expiry, or replay. |
| `403` | Storage policy or filesystem access denied. |
| `404` | Missing target or unknown route. |
| `409` | Name, type, non-empty directory, or lock conflict. |
| `411` | Missing required `Content-Length`. |
| `413` | Provider body, document, or listing limit exceeded. |
| `415` | Wrong `Content-Type`. |
| `503` | Temporary backing-storage outage. |

Keep response bodies short and safe. Do not include a secret, JWT, internal
path, document content, upstream response, or stack trace. The adapter can
surface up to 8 KiB of an error response to Office.

Next: run the [Node.js Provider](nodejs.md), run the [Java Provider](java.md),
or review the [production security checklist](security.md).
