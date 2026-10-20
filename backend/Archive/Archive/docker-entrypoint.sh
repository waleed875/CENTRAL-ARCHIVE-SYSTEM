#!/bin/sh
set -eu

# Wait for the server; the application initializes storage through the MinIO SDK.
if [ "${INITIALIZE_MINIO_BUCKET:-false}" = "true" ]; then
    : "${MINIO_ENDPOINT:?Missing MINIO_ENDPOINT}"
    : "${MINIO_ACCESS_KEY:?Missing MINIO_ACCESS_KEY}"
    : "${MINIO_SECRET_KEY:?Missing MINIO_SECRET_KEY}"
    : "${MINIO_BUCKET:?Missing MINIO_BUCKET}"
    attempt=0
    until curl --fail --silent --show-error --max-time 5 \
        "${MINIO_ENDPOINT%/}/minio/health/ready" >/dev/null; do
        attempt=$((attempt + 1))
        if [ "$attempt" -ge 60 ]; then
            echo "MinIO did not become ready within the startup limit." >&2
            exit 1
        fi
        sleep 2
    done

fi

echo 'Starting Archive (storage bootstrap v2 via MinIO SDK).'
exec java -jar /app/archive.jar
