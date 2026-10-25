"""Remote-only, deterministic real-framework fixtures; no model or external exporter."""
import asyncio
import json
import sys
import tempfile
from pathlib import Path
from eap_execution import Journal
from test_framework_adapters import langgraph_scenario, autogen_scenario


async def main(output):
    output.mkdir()
    with tempfile.TemporaryDirectory() as directory:
        for framework, version, scenario in [("langgraph", "1.2.17", langgraph_scenario),
                                              ("autogen-core", "0.7.5", autogen_scenario)]:
            journal = Journal(Path(directory)/(framework+".sqlite"), "eap-agents/framework-fixture",
                              framework=framework, version=version)
            try:
                body = await scenario(journal)
                (output/(framework+".json")).write_text(json.dumps(body, indent=2)+"\n")
            finally:
                journal.close()


if __name__ == "__main__":
    asyncio.run(main(Path(sys.argv[1])))
