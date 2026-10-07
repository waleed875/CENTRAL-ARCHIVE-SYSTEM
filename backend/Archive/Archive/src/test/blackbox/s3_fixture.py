#!/usr/bin/env python3
"""Provision the isolated live-test bucket using authenticated S3 requests.

Deployment requires the bucket to exist before readiness can pass. This fixture
creates it explicitly; it must not weaken the application's readiness checks.
Uses only the Python standard library and AWS Signature Version 4.
"""
from datetime import datetime, timezone
import hashlib
import hmac
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request

BUCKET = 'archive-live-smoke'


def main():
    endpoint = os.environ['MINIO_ENDPOINT'].rstrip('/')
    parsed = urllib.parse.urlsplit(endpoint)
    if (os.environ.get('GITHUB_ACTIONS') != 'true'
            or os.environ.get('SMOKE_DISPOSABLE_ENV') != '1'
            or parsed.scheme != 'http'
            or parsed.hostname not in {'localhost', '127.0.0.1'}
            or parsed.path or parsed.query or parsed.fragment
            or parsed.username or parsed.password):
        raise SystemExit('MinIO fixture requires an explicit disposable loopback test service')
    access = os.environ['MINIO_ACCESS_KEY']
    secret = os.environ['MINIO_SECRET_KEY']

    def send(method):
        now = datetime.now(timezone.utc)
        timestamp = now.strftime('%Y%m%dT%H%M%SZ')
        day = now.strftime('%Y%m%d')
        empty_hash = hashlib.sha256(b'').hexdigest()
        signed_headers = 'host;x-amz-content-sha256;x-amz-date'
        canonical_headers = (f'host:{parsed.netloc}\n'
                             f'x-amz-content-sha256:{empty_hash}\n'
                             f'x-amz-date:{timestamp}\n')
        canonical = '\n'.join([method, '/' + BUCKET, '', canonical_headers,
                               signed_headers, empty_hash])
        scope = day + '/us-east-1/s3/aws4_request'
        to_sign = '\n'.join(['AWS4-HMAC-SHA256', timestamp, scope,
                             hashlib.sha256(canonical.encode()).hexdigest()])
        def sign(key, value):
            return hmac.new(key, value.encode(), hashlib.sha256).digest()
        key = sign(sign(sign(sign(('AWS4' + secret).encode(), day), 'us-east-1'), 's3'), 'aws4_request')
        signature = hmac.new(key, to_sign.encode(), hashlib.sha256).hexdigest()
        authorization = (f'AWS4-HMAC-SHA256 Credential={access}/{scope}, '
                         f'SignedHeaders={signed_headers}, Signature={signature}')
        request = urllib.request.Request(endpoint + '/' + BUCKET,
            data=b'' if method == 'PUT' else None, method=method,
            headers={'Host':parsed.netloc, 'x-amz-date':timestamp,
                     'x-amz-content-sha256':empty_hash, 'Authorization':authorization})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    before = send('HEAD')
    created = None
    if before == 404:
        created = send('PUT')
        if created != 200:
            raise SystemExit(f'MinIO test bucket creation failed: HTTP {created}')
    elif before != 200:
        raise SystemExit(f'MinIO authenticated bucket check failed: HTTP {before}')
    after = send('HEAD')
    if after != 200:
        raise SystemExit(f'MinIO bucket is not ready after fixture setup: HTTP {after}')
    out = Path(os.environ['RUNNER_TEMP']) / 'live-api-evidence'
    out.mkdir(parents=True, exist_ok=True)
    (out / 'minio-fixture.json').write_text(json.dumps({
        'bucket':BUCKET, 'authenticated':True, 'before_status':before,
        'create_status':created, 'after_status':after,
        'utc':datetime.now(timezone.utc).isoformat()}, indent=2))
    print('Isolated MinIO bucket exists and authenticated HEAD returned HTTP 200', flush=True)


if __name__ == '__main__':
    main()
