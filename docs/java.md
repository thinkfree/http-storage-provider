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
| `HttpStorageProviderApplication.java` | Starts the Spring Boot application and enables typed configuration. |
| `config/StorageProperties.java` | Binds and validates `tfo.storage.*` configuration. |
| `web/StorageController.java` | Implements the Spring MVC request boundary and delegates storage work. |
| `web/StorageExceptionHandler.java` | Maps application and filesystem failures to stable HTTP responses. |
| `web/StorageRouteParser.java` | Parses the exact signed raw URI before document-path conversion. |
| `security/RequestJwtVerifier.java` | Verifies the signed JWT against the actual request before storage access. |
| `service/LocalDirectoryStorageService.java` | Implements the replaceable local storage service. |
| `service/StorageStateStore.java` | Owns local replay, lock, and upload-staging state. |
| `model/StorageEntry.java` | Defines the JSON metadata DTO returned by Spring MVC. |
| `HttpStorageProviderApplicationTest.java` | Starts Spring Boot on a random port and exercises the complete lifecycle and security boundaries. |
| `RequestJwtVerifierTest.java` | Tests original-token validation, claim bindings, verified context, replay, and metadata boundaries. |

The example follows standard Spring constructor injection, typed
`@ConfigurationProperties`, `@RestController`, `@Service`, and
`@RestControllerAdvice` boundaries. `application.properties` maps
`TFO_STORAGE_ROOT` to `tfo.storage.root` and maps the other documented
environment variables in the same way. Applications that already use Spring
Boot can provide the `tfo.storage.*` properties directly in
`application.properties` or `application.yaml`.

`StorageController` serializes bounded INFO/LIST DTOs with Jackson to a byte
array, rejects either body above 5 MiB, sets that array's exact `Content-Length`,
and returns the same bytes. GET uses a Spring `Resource` with the file's known
original length. The configuration and local service reject files above the
300 MiB protocol hard gate before streaming. A custom Spring implementation must not return an
unknown-length `StreamingResponseBody` or rely on chunked transfer for these
three operations.

## Use verified customer context

In `StorageController`, capture the existing verifier call's return value
before capability selection or storage dispatch:

```java
Map<String, Object> verifiedRequest = requestVerifier.verify(
        request.getHeader("X-TFO-Storage-Request-JWT"),
        request.getMethod(), route.rawPath(),
        request.getHeader(HttpHeaders.CONTENT_TYPE),
        requestBody.length(), requestBody.sha256());
Object clientMetadata = verifiedRequest.get("client_metadata");
// Apply your customer session/document/operation policy here before proceeding.
```

Import `java.util.Map` when integrating this snippet. The returned map is the
signed `request`, exposed only after all verification and replay consumption.
If present, `client_metadata` is a verified object map; `get` returns `null`
when omitted. A supplied null or non-object metadata claim is rejected. Validate
your required application fields and resolve the session in your own trusted
store; the sample controller has no customer-session authentication service.

The verifier bounds the original JWT at 8,192 UTF-8 bytes and selects only the
registered adapter key from unverified `request.adapter`. No adapter-header
argument is needed, and an incoming legacy header is ignored. It enforces
depth 8 and Java `String.length()` limits: nonblank keys 64, string values 512.
The 4,096-byte limit is on Office's original input, not on a reserialized map.
See [delivery and limits](protocol.md#pass-customer-context) and
[customer authorization](security.md#authorize-customer-access).

## Run the tests and package the server

```bash
mvn test
mvn package
```

The expected result is eleven passing lifecycle/security/verifier tests and this executable
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
