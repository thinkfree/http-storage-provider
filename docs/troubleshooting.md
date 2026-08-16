# Troubleshoot a Provider connection

Use the visible Office or Provider symptom to select a safe check. Do not print
the request JWT secret or complete JWT while diagnosing a connection.

| Symptom | Check | Recovery |
| --- | --- | --- |
| Registration or listing reports connection refused | From the Office runtime, resolve and connect to the Provider host and port. | Use a Provider address reachable from the Office container, Pod, VM, or host. Do not use that runtime's own `127.0.0.1`. |
| Every operation returns `401 Unauthorized` | Compare the exact adapter name and secret in both systems; check Provider and Office clocks. | Restore the same immutable adapter identity and at least 32-byte secret, then synchronize clocks. Do not log either secret or JWT. |
| One request returns `401` after a successful identical request | Check whether the same JWT or `jti` was retried by a proxy or test tool. | Create a newly signed request. The Provider intentionally rejects replay until expiry. |
| JSON operations return `415` | Inspect only the request `Content-Type`. | Send exact `application/json`; `put` uses `application/octet-stream`. |
| `put` returns `400`, `411`, or `413` | Compare fixed `Content-Length`, received bytes, the configured limit, the 300 MiB protocol hard gate, and available staging space. | Send the complete body with fixed length, lower the document size, or free staging space. A configured limit may be lowered but not raised above 300 MiB. Do not enable chunked PUT. |
| `info` works but `list` fails | Validate that every entry is a direct child with a unique path and required metadata. | Return only direct children, no unknown fields, no more than 10,000 entries, and `size=0` for directories. |
| INFO, LIST, or GET reports a response framing or size error | Inspect the successful response headers and compare bytes actually sent. | Send exactly one decimal `Content-Length`, the required media type, no `Transfer-Encoding` or `Content-Encoding`, and exactly that many body bytes. Keep each INFO/LIST body at or below 5 MiB and each document at or below 300 MiB. |
| A path returns `403` | Check for a symbolic link, filesystem permission, or tenant-root policy denial. | Move the document inside the configured root or grant the minimum required storage permission. Do not weaken traversal protection. |
| Rename or delete returns `409` | Check current lock, destination name, item type, and whether a directory is empty. | Release the correct owner's lock, select an unused name, or delete children explicitly before the directory. |
| The browser lists no sample file | Confirm the selected example's storage root and `Welcome.txt`. | Node.js uses root `storage/`; Java uses `examples/java/storage/`. Start only the intended Provider and use its printed adapter name. |
| Save succeeds but reopening shows old content | Compare the configured root with the file inspected on disk, then check the `put` response and target revision. | Point Office to the running Provider, verify the same document path, and inspect that Provider's storage root. |

If a failure remains, record a correlation ID, HTTP status, operation, adapter
name, and normalized document path. Redact request JWTs, secrets, document
content, and internal storage credentials before sharing diagnostics.
