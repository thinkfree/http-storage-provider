# Run the Python local-directory Provider

The Python 3.12 example under `examples/python/` is a complete Provider server
implemented with the Python standard library. It uses
`examples/python/storage/` to make clone-and-run evaluation possible. The local
directory is an example backing store; production integrations should replace
those filesystem calls without changing the signed protocol.

## Start the server

```bash
cd examples/python
python3 run.py
```

The first run creates an ignored `.provider-config.json` with the stable adapter
name `local-directory-python`, a random 256-bit request JWT secret, and port
`8080`. Copy the printed values into the Office HTTP Storage form. The root
listing immediately contains the tracked Word, Cell, and Show samples below
`storage/samples/`.

Stop another example server using port `8080` before starting this one.

## Run the tests

```bash
python3 -m unittest -v
```

The expected result is two passing tests. The suite exercises all nine storage
operations against real files, a complete save, fixed download length, replay
rejection, traversal rejection, and root protection.

`provider.py` owns routing, JWT verification, local storage, replay state,
locks, and PUT staging. `run.py` owns only local initialization and startup.
This separation lets a framework-based Python service reuse `StorageProvider`
and replace the HTTP or storage boundary deliberately.
