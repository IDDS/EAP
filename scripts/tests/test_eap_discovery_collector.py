from __future__ import annotations

import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import eap_discovery_collector as collector  # noqa: E402


def completed(args: list[str], stdout: str) -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess(args=args, returncode=0, stdout=stdout, stderr="")


class DockerDiscoveryTests(unittest.TestCase):
    def test_discovers_agent_backend_without_sending_secret_values(self) -> None:
        listed = json.dumps({"ID": "abc123def456"}) + "\n"
        inspected = json.dumps([{
            "Id": "abc123def4567890",
            "Name": "/boss-agent-backend",
            "Config": {
                "Image": "boss-agent-backend:latest",
                "Labels": {
                    "com.docker.compose.project": "boss-agent",
                    "com.docker.compose.service": "backend",
                },
                "Entrypoint": ["uvicorn"],
                "Cmd": ["main:app", "--host", "0.0.0.0", "--port", "8000"],
                "Env": ["OPENAI_API_KEY=super-secret", "APP_ENV=production"],
            },
            "HostConfig": {"Privileged": False, "PidMode": "", "NetworkMode": "boss-agent_default"},
            "Mounts": [],
            "NetworkSettings": {
                "Networks": {"boss-agent_default": {}},
                "Ports": {"8000/tcp": [{"HostIp": "0.0.0.0", "HostPort": "8000"}]},
            },
            "State": {"Status": "running"},
        }])
        with patch.object(collector.subprocess, "run", side_effect=[
            completed(["docker", "ps"], listed),
            completed(["docker", "inspect"], inspected),
        ]):
            observations = collector.docker_observations()

        self.assertEqual(len(observations), 1)
        self.assertEqual(observations[0]["product"], "HelloBoss")
        self.assertEqual(observations[0]["sourceType"], "DOCKER")
        self.assertEqual(observations[0]["evidence"]["llmCredentialEnvKeys"], ["OPENAI_API_KEY"])
        self.assertNotIn("super-secret", json.dumps(observations))

    def test_skips_platform_and_infrastructure_containers(self) -> None:
        rows = [{"ID": value} for value in ("eap1", "front1", "mysql1")]
        documents = [
            {
                "Id": "eap1", "Name": "/eap-backend",
                "Config": {"Image": "eap-backend", "Labels": {"com.docker.compose.project": "eap", "com.docker.compose.service": "backend"}, "Env": []},
                "HostConfig": {}, "Mounts": [], "NetworkSettings": {}, "State": {"Status": "running"},
            },
            {
                "Id": "front1", "Name": "/boss-agent-frontend",
                "Config": {"Image": "boss-agent-frontend", "Labels": {"com.docker.compose.project": "boss-agent", "com.docker.compose.service": "frontend"}, "Env": []},
                "HostConfig": {}, "Mounts": [], "NetworkSettings": {}, "State": {"Status": "running"},
            },
            {
                "Id": "mysql1", "Name": "/boss-agent-mysql",
                "Config": {"Image": "mysql:8.0", "Labels": {"com.docker.compose.project": "boss-agent", "com.docker.compose.service": "mysql"}, "Env": []},
                "HostConfig": {}, "Mounts": [], "NetworkSettings": {}, "State": {"Status": "running"},
            },
        ]
        with patch.object(collector.subprocess, "run", side_effect=[
            completed(["docker", "ps"], "\n".join(json.dumps(row) for row in rows)),
            completed(["docker", "inspect"], json.dumps(documents)),
        ]):
            self.assertEqual(collector.docker_observations(), [])


if __name__ == "__main__":
    unittest.main()
