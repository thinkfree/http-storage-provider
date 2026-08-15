## Outcome

<!-- What can a Provider developer or operator do after this change? -->

## Protocol and compatibility

<!-- List affected routes, headers, claims, fields, or operation semantics. Write None when unchanged. -->

## Security

<!-- Describe trust-boundary, secret, path, replay, lock, or storage effects. -->

## Verification

- [ ] `npm run check`
- [ ] `mvn test` in `examples/java`
- [ ] `.venv/bin/ruff check .` and `.venv/bin/ruff format --check .` in `examples/python`
- [ ] `.venv/bin/python -m unittest -v` in `examples/python`
- [ ] Sample OOXML hashes and ZIP structures verified
- [ ] No secret, JWT, credential, or customer document added
