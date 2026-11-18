#!/usr/bin/env python3
"""Read-only migration benchmark collector.

Requires Python 3.10+, no third-party packages.
Reads token from MIGRATION_BENCHMARK_TOKEN (never from command-line arguments).
Optionally samples container memory with docker stats --no-stream.
"""
from __future__ import annotations

import argparse
import csv
import datetime as dt
import json
import os
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


FIELDS = (
    "timestamp_utc", "batch_id", "batch_status", "completed_files",
    "transferred_bytes", "files_per_minute", "mib_per_second",
    "estimated_seconds_remaining", "seconds_since_last_completion",
    "stalled", "active_file_count", "average_read_decode_ms",
    "average_upload_ms", "average_metadata_ms", "docker_mem_usage",
    "docker_mem_percent", "sample_error",
)


def get_json(base: str, endpoint: str, token: str, timeout: float) -> dict:
    request = Request(
        base.rstrip("/") + endpoint,
        headers={"Authorization": "Bearer " + token, "Accept": "application/json"},
    )
    with urlopen(request, timeout=timeout) as response:
        parsed = json.load(response)
    if not isinstance(parsed, dict) or "data" not in parsed:
        raise ValueError("Unexpected API response shape")
    return parsed


def docker_memory(container: str, timeout: float) -> tuple[str, str]:
    """Best-effort local container memory. No docker daemon access is required otherwise."""
    if not container:
        return "", ""
    try:
        result = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}|{{.MemPerc}}", container],
            capture_output=True, text=True, check=True, timeout=timeout,
        )
        usage, percent = result.stdout.strip().split("|", 1)
        return usage, percent
    except (OSError, ValueError, subprocess.SubprocessError):
        return "", ""


def sample(base: str, batch: str, token: str, timeout: float, container: str) -> dict:
    row = {key: "" for key in FIELDS}
    row["timestamp_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
    row["batch_id"] = batch
    status_data = get_json(base, "/api/v1/migration-batches/" + batch, token, timeout)["data"]
    if not isinstance(status_data, dict):
        raise ValueError("Missing batch metadata")
    row["batch_status"] = status_data.get("status", "")
    live_data = get_json(
        base, "/api/v1/migration-batches/" + batch + "/live-progress", token, timeout
    )["data"]
    if isinstance(live_data, dict):
        columns = {
            "completed_files": "completedFiles",
            "transferred_bytes": "transferredBytes",
            "files_per_minute": "filesPerMinute",
            "mib_per_second": "mibPerSecond",
            "estimated_seconds_remaining": "estimatedSecondsRemaining",
            "seconds_since_last_completion": "secondsSinceLastCompletion",
            "stalled": "stalled",
            "average_read_decode_ms": "averageReadDecodeMs",
            "average_upload_ms": "averageUploadMs",
            "average_metadata_ms": "averageMetadataMs",
        }
        for column, key in columns.items():
            value = live_data.get(key)
            row[column] = "" if value is None else value
        row["active_file_count"] = len(live_data.get("activeSourceFiles") or [])
    row["docker_mem_usage"], row["docker_mem_percent"] = docker_memory(container, timeout)
    return row


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", required=True, help="staging backend origin, e.g. http://localhost:8080")
    parser.add_argument("--batch-id", required=True, help="UUID of an already-running batch")
    parser.add_argument("--output", required=True, help="CSV path")
    parser.add_argument("--duration", type=int, default=900, help="maximum sample duration in seconds")
    parser.add_argument("--interval", type=float, default=3.0, help="poll period in seconds, minimum 2")
    parser.add_argument("--timeout", type=float, default=8.0, help="request timeout")
    parser.add_argument("--docker-container", default="", help="optional local backend container name")
    args = parser.parse_args()

    token = os.environ.get("MIGRATION_BENCHMARK_TOKEN", "").strip()
    if not token:
        parser.error("set MIGRATION_BENCHMARK_TOKEN in the environment")
    if args.duration < 1 or args.interval < 2 or args.timeout <= 0:
        parser.error("duration must be positive; interval >= 2; timeout > 0")
    # Ensure CSV exists even if the first request fails.
    with open(args.output, "w", newline="", encoding="utf-8") as out:
        writer = csv.DictWriter(out, fieldnames=FIELDS)
        writer.writeheader()
        deadline = time.monotonic() + args.duration
        failures = 0
        count = 0
        while time.monotonic() < deadline:
            tick = time.monotonic()
            try:
                row = sample(args.url, args.batch_id, token, args.timeout, args.docker_container)
                failures = 0
            except (HTTPError, URLError, ValueError, OSError, json.JSONDecodeError) as error:
                failures += 1
                row = {key: "" for key in FIELDS}
                row["timestamp_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
                row["batch_id"] = args.batch_id
                # Do not serialize HTTP error bodies or URLs: they may contain secrets.
                row["sample_error"] = type(error).__name__
            writer.writerow(row)
            out.flush()
            count += 1
            if row["batch_status"] in {"SUCCESS", "PARTIAL", "FAILED", "CANCELLED"}:
                break
            if failures >= 5:
                print("Five consecutive sampling errors; stopping", file=sys.stderr)
                return 2
            delay = min(args.interval - (time.monotonic() - tick), deadline - time.monotonic())
            if delay > 0:
                time.sleep(delay)
    print(f"Saved {count} samples to {args.output}")
    print("Metrics are process-local and windowed; use staging only and compare equal workloads.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
