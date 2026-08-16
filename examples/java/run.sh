#!/usr/bin/env sh
set -eu

SCRIPT_DIRECTORY=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIRECTORY"

CONFIGURATION_FILE=.env.java
if [ ! -f "$CONFIGURATION_FILE" ]; then
  umask 077
  SECRET=$(openssl rand -base64 32 | tr -d '\n')
  {
    echo 'TFO_STORAGE_HOST=127.0.0.1'
    echo 'TFO_STORAGE_PORT=8080'
    echo 'TFO_STORAGE_ROOT=./storage'
    echo 'TFO_STORAGE_ROOT_NAME=Documents'
    echo 'TFO_STORAGE_ADAPTER=local-directory-java'
    echo "TFO_STORAGE_REQUEST_JWT_SECRET=$SECRET"
    echo 'TFO_STORAGE_MAX_DOCUMENT_BYTES=314572800'
  } > "$CONFIGURATION_FILE"
  mkdir -p storage
  echo 'Created an ignored local configuration in examples/java/.env.java.'
fi

set -a
# This file is generated locally with fixed key names and is never committed.
. "./$CONFIGURATION_FILE"
set +a

echo "Adapter name: $TFO_STORAGE_ADAPTER"
echo "Request JWT secret: $TFO_STORAGE_REQUEST_JWT_SECRET"
echo "Provider base URL: http://$TFO_STORAGE_HOST:$TFO_STORAGE_PORT"

mvn -q -DskipTests package
exec java -jar target/tfo-http-storage-java-provider-0.1.0-SNAPSHOT.jar
