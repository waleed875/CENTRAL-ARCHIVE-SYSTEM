"""Unit tests for the read-only staging benchmark collector."""
import csv
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.error import URLError

import migration_benchmark as bench


class BenchmarkCollectorTest(unittest.TestCase):
    def test_sample_maps_api_telemetry_without_exposing_active_file_keys(self):
        batch = {"status": "RUNNING"}
        telemetry = {
            "completedFiles": 4, "transferredBytes": 2 * 1048576,
            "filesPerMinute": 10.0, "mibPerSecond": 3.0,
            "estimatedSecondsRemaining": 30, "secondsSinceLastCompletion": 1,
            "stalled": False, "activeSourceFiles": ["FILE-100", "FILE-200"],
            "averageReadDecodeMs": 11.2,
            "averageUploadMs": 20.0, "averageMetadataMs": 3.0,
        }
        with patch.object(bench, "get_json", side_effect=[{"data": batch}, {"data": telemetry}]), \
                patch.object(bench, "docker_memory", return_value=("128MiB / 1GiB", "12.5%")):
            sample = bench.sample("http://localhost:8080", "abc", "secret", 5, "backend")
        self.assertEqual(sample["completed_files"], 4)
        self.assertEqual(sample["transferred_bytes"], 2 * 1048576)
        self.assertEqual(sample["active_file_count"], 2)
        self.assertEqual(sample["docker_mem_percent"], "12.5%")
        self.assertNotIn("FILE-100", str(sample))
        self.assertNotIn("secret", str(sample))

    def test_no_live_snapshot_keeps_blank_fields_not_fake_zeros(self):
        with patch.object(bench, "get_json", side_effect=[
            {"data": {"status": "RUNNING"}}, {"data": None}
        ]):
            sample = bench.sample("http://localhost:8080", "abc", "token", 5, "")
        self.assertEqual(sample["completed_files"], "")
        self.assertEqual(sample["batch_status"], "RUNNING")
        self.assertEqual(sample["docker_mem_usage"], "")

    def test_network_errors_propagate_for_capped_retries(self):
        with patch.object(bench, "get_json", side_effect=URLError("unreachable")):
            with self.assertRaises(URLError):
                bench.sample("http://localhost:8080", "abc", "token", 5, "")

    def test_docker_probe_is_optional(self):
        with patch.object(bench.subprocess, "run") as run:
            self.assertEqual(bench.docker_memory("", 3), ("", ""))
            run.assert_not_called()

    def test_csv_headers_cover_all_sample_columns(self):
        buffer = io.StringIO()
        writer = csv.DictWriter(buffer, fieldnames=bench.FIELDS)
        writer.writeheader()
        writer.writerow({key: "" for key in bench.FIELDS})
        self.assertEqual(len(buffer.getvalue().splitlines()), 2)
        self.assertIn("mib_per_second", buffer.getvalue().splitlines()[0])


if __name__ == "__main__":
    unittest.main()
