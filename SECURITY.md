# Security policy

## Report a vulnerability privately

Do not open a public issue for a suspected authentication bypass, path escape,
secret exposure, replay weakness, lock isolation failure, arbitrary file
access, or document-integrity issue.

Use the repository's
[private vulnerability report](https://github.com/thinkfree/http-storage-provider/security/advisories/new).
Include the affected commit, operation, impact, minimal reproduction, and a
safe contact method. Redact request JWT secrets, live JWTs, customer documents,
credentials, and internal service addresses.

## Supported code

This repository is currently Preview. Security fixes target the current default
branch. No stable release or long-term support window has been declared.

## Security boundary

The reference servers verify signed Office requests and contain local paths,
but an operator remains responsible for TLS, network policy, tenant
authorization, secret management, backing-store access control, shared replay
and lock state, retention, backup, monitoring, and incident response. Read the
[production security checklist](docs/security.md) before deployment.
