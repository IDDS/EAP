"""Deterministic workload, random event identities; run only in an isolated test directory."""
import json
import sys
import tempfile
from pathlib import Path
from eap_execution import Journal
from test_eap_execution import scenario

with tempfile.TemporaryDirectory() as directory:
    journal = Journal(Path(directory) / "fixture.sqlite", "eap-agents/fixture")
    try:
        scenario(journal, directory)
        payload = dict(schemaVersion=1, producerId=journal.producer_id, framework=journal.framework,
                       frameworkVersion=journal.version, events=journal.pending())
        Path(sys.argv[1]).write_text(json.dumps(payload, indent=2) + "\n")
        print("SDK_FIXTURE_GENERATED", len(payload["events"]))
    finally:
        journal.close()
