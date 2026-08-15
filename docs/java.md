# Run the Java local-directory Provider

The Java 17 example under `examples/java/` is a complete Spring Boot Provider
server. It uses `examples/java/storage/` to keep the example focused on the
protocol. This local-directory design is for runnable evaluation; replace the
filesystem methods with your production storage implementation while
preserving the verified request boundary.

## Start the server

Prerequisites: Java 17 or later, Maven 3.9 or later, and OpenSSL.

```bash
cd examples/java
./run.sh
```

On its first run, the script creates an ignored `.env.java` with a stable
adapter name and random secret. It builds an executable Spring Boot JAR and
starts the embedded server. The expected root listing contains the tracked
Word, Cell, and Show documents below `storage/samples/`.

Stop another Provider using port `8080` before running this command.

## Understand the source

| Source | Responsibility |
| --- | --- |
| `ProviderApplication.java` | Starts the Spring Boot application and scans external configuration. |
| `ProviderConfig.java` | Binds and validates Spring configuration, environment values, and storage limits. |
| `TfoStorageRequestVerifier.java` | Verifies the signed JWT against the actual request before storage access. |
| `LocalDirectoryProvider.java` | Implements the Spring MVC boundary, all routes, local storage, replay protection, locks, and staging. |
| `LocalDirectoryProviderTest.java` | Starts Spring Boot on a random port and exercises the complete lifecycle, replay rejection, and path protection. |

The example `application.properties` maps `TFO_STORAGE_ROOT` to
`tfo.storage.root` and maps the other documented environment variables in the
same way. Applications that already use Spring Boot can provide the
`tfo.storage.*` properties directly in `application.properties` or
`application.yaml` without changing the Provider logic.

## Run the tests and package the server

```bash
mvn test
mvn package
```

The expected result is two passing lifecycle/security tests and this executable
artifact:

```text
target/tfo-http-storage-java-provider-0.1.0-SNAPSHOT.jar
```

Run the artifact directly after exporting the variables shown by `run.sh`:

```bash
java -jar target/tfo-http-storage-java-provider-0.1.0-SNAPSHOT.jar
```

The Java and Node.js examples use the same environment keys and operation
semantics. See the [Node.js configuration table](nodejs.md#configure-the-server)
and the [protocol reference](protocol.md).
