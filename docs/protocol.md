# TFO HTTP Storage Protocol v1 reference

The packaged Self-hosted Office HTTP Storage adapter calls this Provider
contract. It is a storage protocol between Office and a customer-operated
Provider, not a general-purpose HTTP proxy or a Thinkfree-hosted public API.

For Provider base URL `{BASE_URL}` and document path `{DOCUMENT_PATH}`, Office
calls:

```text
{BASE_URL}/tfo-storage/v1/{ENCODED_DOCUMENT_PATH}/{OPERATION}
```

Each UTF-8 path segment is percent-encoded independently. The Provider root has
an empty document path, so its list route is:

```text
{BASE_URL}/tfo-storage/v1/list
```

The base URL must use HTTP or HTTPS and cannot contain credentials, a query,
fragment, or dot segment. The protocol does not use query strings, cookies,
redirects, arbitrary forwarding headers, or compatibility routes.

## Authenticate every request

Every operation includes these headers:

```http
X-TFO-Storage-Adapter: customer-storage-a
X-TFO-Storage-Request-JWT: eyJhbGciOiJIUzI1NiIsInR5cCI6InRmby1zdG9yYWdlLXJlcXVlc3Qrand0In0...
```

The JWT uses `HS256` and header `typ=tfo-storage-request+jwt`. Resolve the
secret from the configured adapter identity. Never accept a secret in the URL,
request body, or another caller-selected field.

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
    "path": "/office/tfo-storage/v1/contracts/sample.docx/put",
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
| `request.adapter` | signed request | string | Yes | Matches `X-TFO-Storage-Adapter` and one configured Provider connection. |
| `request.method` | signed request | string | Yes | Matches the actual uppercase HTTP method. |
| `request.path` | signed request | string | Yes | Matches the actual raw encoded path, including the Provider base path. |
| `request.content_length` | signed request | integer | Yes | Exact body byte length; `0` when no body exists. |
| `request.content_sha256` | signed request | string | Yes | Lowercase SHA-256 of the exact body. Use the empty-body digest when no body exists. |
| `request.content_type` | signed request | string | When sent | Matches the actual `Content-Type` exactly. |
| `request.office_connection_id` | signed request | string | No | Opaque Office runtime context. It is not authorization. |
| `request.arguments` | signed request | object | No | Operation context such as `save_type`. Unknown values are not authorization. |
| `request.client_metadata` | signed request | object | No | Caller metadata, maximum 2,048 UTF-8 bytes. Its signature protects transit integrity but does not make it Office identity. |

The complete JWT cannot exceed 5,120 UTF-8 bytes. The adapter creates a new
`jti` for every request. A Provider must retain used IDs until expiry in an
atomic store shared by every replica.

## Implement the endpoint catalog

| Role | Method | Path suffix | Request | Success response |
| --- | --- | --- | --- | --- |
| Read metadata | `GET` | `/{path}/info` | No body | JSON entry; a missing item is `404`. |
| List direct children | `GET` | `/{path}/list` | No body | JSON object with `entries`. |
| Download | `GET` | `/{path}/get` | No body | Raw bytes and accurate `Content-Length`. |
| Save | `PUT` | `/{path}/put` | Raw bytes and fixed `Content-Length` | Optional revision or result text. |
| Lock | `POST` | `/{path}/lock` | `{"owner":"..."}` | Any `2xx`. |
| Unlock | `POST` | `/{path}/unlock` | `{"owner":"..."}` | Any `2xx`. |
| Create directory | `POST` | `/{parent}/mkdir` | `{"name":"..."}` | Any `2xx`. |
| Rename | `POST` | `/{path}/rename` | `{"name":"..."}` | Any `2xx`. |
| Delete | `DELETE` | `/{path}/delete` | No body | Any `2xx`. |

`PUT` uses `application/octet-stream`. The four JSON operations use
`application/json` and exactly the documented one-field object. The packaged
adapter sends a fixed `Content-Length`; chunked request transfer is not part of
the protocol.

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

- `get` streams the exact stored bytes and reports their original size.
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

Any non-`2xx` response fails the Office operation. `info` uses `404` for a
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
