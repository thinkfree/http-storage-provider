# Troubleshoot a Provider connection

Use the visible Office or Provider symptom to select a safe check. Do not print
the request JWT secret or complete JWT while diagnosing a connection.

| Symptom | Check | Recovery |
| --- | --- | --- |
| Registration or listing reports connection refused | From the Office runtime, resolve and connect to the Provider host and port. | Use a Provider address reachable from the Office container, Pod, VM, or host. Do not use that runtime's own `127.0.0.1`. |
| Every operation returns `401 Unauthorized` | Compare the exact adapter name and secret in both systems; check Provider and Office clocks. | Restore the same immutable adapter identity and at least 32-byte secret, then synchronize clocks. Do not log either secret or JWT. |
| Provider demands `X-TFO-Storage-Adapter` | Check whether the deployed verifier still uses the removed header to select a key. | Use bounded JWT `request.adapter` only as the registered-key lookup hint, then verify the original JWT and actual request. Ignore legacy header values. |
| A valid metadata object is rejected for exceeding 4 KiB in the JWT | Check which byte boundary is being enforced. | Office limits the original input JSON to 4,096 bytes; the Provider limits the complete JWT to 8,192 bytes. Numeric reserialization can grow metadata; do not apply another metadata byte cap. |
| Opening fails before the editor appears | Check the authenticated `info` result and your customer session/read policy. | System-adapter opens require `info` after internal `start`. Correct missing-document or access failures; do not bypass `info` or later GET/PUT authorization. |
| One request returns `401` after a successful identical request | Check whether the same JWT or `jti` was retried by a proxy or test tool. | Create a newly signed request. The Provider intentionally rejects replay until expiry. |
| JSON operations return `415` | Inspect only the request `Content-Type`. | Send exact `application/json`; `put` uses `application/octet-stream`. |
| `put` returns `400`, `411`, or `413` | Compare fixed `Content-Length`, received bytes, the configured limit, the 300 MiB protocol hard gate, and available staging space. | Send the complete body with fixed length, lower the document size, or free staging space. A configured limit may be lowered but not raised above 300 MiB. Do not enable chunked PUT. |
| `info` works but `list` fails | Validate that every entry is a direct child with a unique path and required metadata. | Return only direct children, no unknown fields, no more than 10,000 entries, and `size=0` for directories. |
| INFO, LIST, GET, or PUT reports a response framing or size error | Inspect the successful response headers and compare bytes actually sent. | Send exactly one decimal `Content-Length`, the required media type, no `Transfer-Encoding` or `Content-Encoding`, and exactly that many body bytes. Keep each INFO/LIST/PUT body at or below 5 MiB and each document at or below 300 MiB. |
| A path returns `403` | Check for a symbolic link, filesystem permission, or tenant-root policy denial. | Move the document inside the configured root or grant the minimum required storage permission. Do not weaken traversal protection. |
| Rename or delete returns `409` | Check current lock, destination name, item type, and whether a directory is empty. | Release the correct owner's lock, select an unused name, or delete children explicitly before the directory. |
| The browser lists no sample file | Confirm the selected example's storage root and `Welcome.txt`. | Node.js uses root `storage/`; Java uses `examples/java/storage/`. Start only the intended Provider and use its printed adapter name. |
| Save succeeds but reopening shows old content | Compare the configured root with the file inspected on disk, then check the `put` response and target revision. | Point Office to the running Provider, verify the same document path, and inspect that Provider's storage root. |
| Reopening shows a synchronization dialog or Office error `599` | Inspect the underlying adapter error; the dialog alone does not identify the cause. A lock owned by the previous session can reject the new session. | Wait for Office's normal document close and lock release, then reopen the same path with its stable `docId`. Closing a browser context alone does not guarantee normal Office close. Do not delete lock records or bypass owner checks. |

If a failure remains, record a correlation ID, HTTP status, operation, adapter
name, and normalized document path. Redact request JWTs, secrets, document
content, and internal storage credentials before sharing diagnostics.
