import importlib.util
import pathlib
import sys
import tempfile
import unittest.mock
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("eap_ebpf_observer.py")
SPEC = importlib.util.spec_from_file_location("eap_ebpf_observer", MODULE_PATH)
observer = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
sys.modules[SPEC.name] = observer
SPEC.loader.exec_module(observer)


class EbpfObserverTests(unittest.TestCase):
    def test_parses_ipv4_and_mapped_ipv6(self):
        ipv4 = observer.parse_event_line("EAPBPF\t10\t42\tboss-agent\t4\t203.0.113.4\t443\n")
        mapped = observer.parse_event_line("EAPBPF\t11\t43\tworker\t6\t::ffff:127.0.0.1\t8080\n")

        self.assertEqual(ipv4.remote_address, "203.0.113.4")
        self.assertEqual(ipv4.remote_port, 443)
        self.assertEqual(mapped.family, 4)
        self.assertEqual(mapped.remote_address, "127.0.0.1")

    def test_rejects_invalid_event_lines(self):
        self.assertIsNone(observer.parse_event_line("noise"))
        self.assertIsNone(observer.parse_event_line("EAPBPF\tx\t42\ta\t4\t127.0.0.1\t443"))
        self.assertIsNone(observer.parse_event_line("EAPBPF\t1\t42\ta\t4\t127.0.0.1\t99999"))

    def test_merges_docker_identity_for_agent_workload(self):
        container_id = "a" * 64
        workload = observer.DockerWorkload(container_id, "boss-agent-backend", "boss:latest", "boss-agent", "backend")
        event = observer.ConnectEvent(1, 42, "python", 4, "203.0.113.10", 443)

        result = observer.build_observation(
            event,
            "worker-01",
            {container_id: workload},
            {"executable": "/usr/bin/python", "cmdline": "python app.py", "cgroup": f"0::/docker/{container_id}"},
        )

        self.assertEqual(result["processName"], "boss-agent-backend")
        self.assertEqual(result["identityKey"], "docker:worker-01:boss-agent:backend")
        self.assertEqual(result["containerId"], container_id)
        self.assertEqual(result["protocolHint"], "HTTPS")

    def test_filters_infrastructure_and_eap_processes(self):
        for value in ("tat_agent", "/usr/local/qcloud/tat_agent", "eap-discovery-agent", "node_exporter"):
            self.assertFalse(observer.looks_agent(value), value)
        self.assertTrue(observer.looks_agent("boss-agent-backend"))
        self.assertTrue(observer.looks_agent("langgraph worker"))

    def test_process_identity_without_container(self):
        event = observer.ConnectEvent(1, 99, "agent.py", 6, "2001:db8::1", 8443)
        result = observer.build_observation(
            event,
            "worker-02",
            {},
            {"executable": "/opt/agent/agent.py", "cmdline": "python agent.py", "cgroup": ""},
        )
        self.assertEqual(result["identityKey"], "process:worker-02:/opt/agent/agent.py")
        self.assertEqual(result["remoteAddress"], "2001:db8::1")

    def test_upload_uses_machine_credential_without_returning_it(self):
        with tempfile.TemporaryDirectory() as directory:
            state_path = pathlib.Path(directory) / "state.json"
            state_path.write_text('{"credential":"machine-secret"}', encoding="utf-8")
            config = {
                "server_url": "https://eap.example",
                "state_dir": directory,
            }

            class Response:
                def __enter__(self):
                    return self

                def __exit__(self, *_args):
                    return False

                def read(self):
                    return b'{"success":true,"data":{"observationsRecorded":1}}'

            with unittest.mock.patch.object(observer.urllib.request, "urlopen", return_value=Response()) as request:
                result = observer.upload(config, [{"processName": "boss-agent"}])

            sent = request.call_args.args[0]
            self.assertEqual(sent.get_header("X-eap-agent-credential"), "machine-secret")
            self.assertEqual(result["observationsRecorded"], 1)
            self.assertNotIn("machine-secret", str(result))


if __name__ == "__main__":
    unittest.main()
