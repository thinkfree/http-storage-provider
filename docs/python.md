# Run the Python local-directory Provider

The Python 3.12 example under `examples/python/` is a complete Provider server
implemented with FastAPI and Uvicorn. It uses `examples/python/storage/` to
make clone-and-run evaluation possible. The local directory is an example
backing store; production integrations should replace those filesystem calls
without changing the signed protocol.

## Start the server

```bash
cd examples/python
./run.sh
```

The script creates an ignored virtual environment, installs the pinned FastAPI
and Uvicorn dependencies, and starts the server. The first run creates an
ignored `.provider-config.json` with the stable adapter name
`local-directory-python`, a random 256-bit request JWT secret, and port `8080`.
Copy the printed values into the Office HTTP Storage form. The root listing
immediately contains the tracked Word, Cell, and Show samples below
`storage/samples/`.

Stop another example server using port `8080` before starting this one.

## Run the tests

```bash
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/ruff check .
.venv/bin/ruff format --check .
.venv/bin/python -m unittest -v
```

The expected result is four passing tests. The suite exercises all nine storage
operations against real files, a complete save, fixed download length, replay
rejection, traversal rejection, and root protection.

The example follows the usual FastAPI application-factory and dependency
injection layout:

| Source | Responsibility |
| --- | --- |
| `app/main.py` | Creates the FastAPI application and registers exception handlers. |
| `app/config.py` | Loads and validates environment values with Pydantic Settings. |
| `app/api/routes.py` | Defines the `APIRouter`, streams request bodies, and returns FastAPI responses. |
| `app/api/dependencies.py` | Exposes typed FastAPI dependencies from application state. |
| `app/models.py` | Defines Pydantic response and request models. |
| `app/security.py` | Verifies the signed JWT against the actual ASGI request. |
| `app/services/storage.py` | Implements the replaceable local storage service. |
| `app/state.py` | Owns local replay, lock, and upload-staging state. |
| `run.py` | Creates the local example configuration and starts the Uvicorn application factory. |

Production integrations can replace `LocalDirectoryStorageService` while
retaining the router, Pydantic models, security verifier, and exception
handlers.

The router uses `JSONResponse` for bounded INFO/LIST models; Starlette renders
the bytes at construction, rejects either body above 5 MiB, and publishes their
exact `Content-Length`. GET uses `FileResponse`, which determines the original
file size before streaming; settings and the local service reject files above
the 300 MiB protocol hard gate first. A
replacement FastAPI response must preserve those fixed-length semantics and
must not return an unbounded `StreamingResponse` with chunked transfer.
