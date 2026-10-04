"""Exercise release eligibility with a fake GitHub CLI; never call the network."""

import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest


class ReleaseGateTest(unittest.TestCase):
    def test_eligibility_and_retries(self):
        workflow = Path(__file__).resolve().parents[1] / "workflows/release.yml"
        # Execute the first run block itself, keeping the workflow as the source.
        block = workflow.read_text().split("        run: |\n", 1)[1]
        script = textwrap.dedent(block.split("\n      - ", 1)[0])
        cases = [
            ("merged", {}, True, 0),
            ("superseded", {"MOCK_HEAD": "newer-sha"}, False, 0),
            ("direct push", {"MOCK_MERGED": "0"}, False, 0),
            ("published", {"MOCK_RELEASE": "published"}, False, 0),
            ("retry draft", {"MOCK_RELEASE": "draft"}, True, 0),
            ("wrong draft", {"MOCK_RELEASE": "wrong-draft"}, False, 1),
            ("API failure", {"MOCK_API_ERROR": "1"}, False, 1),
        ]
        with tempfile.TemporaryDirectory(prefix="impulsefy-release-test-") as tmp:
            root = Path(tmp)
            fake = root / "gh"
            fake.write_text(textwrap.dedent("""\
                #!/usr/bin/env python3
                import json, os, sys
                args = sys.argv[1:]
                if args[0] == "api" and os.environ.get("MOCK_API_ERROR"):
                    sys.exit(1)
                if args[0] == "api" and args[1].endswith("/branches/main"):
                    print(os.environ.get("MOCK_HEAD", "approved-sha"))
                elif args[0] == "api" and args[1].endswith("/pulls"):
                    print(os.environ.get("MOCK_MERGED", "1"))
                elif args[:2] == ["release", "view"]:
                    kind = os.environ.get("MOCK_RELEASE", "missing")
                    if kind == "missing":
                        sys.exit(1)
                    print(json.dumps({
                        "isDraft": kind != "published",
                        "targetCommitish": "other-sha" if kind == "wrong-draft" else "approved-sha",
                    }))
                else:
                    sys.exit("Unexpected GitHub operation: " + repr(args))
                """))
            fake.chmod(0o700)
            for name, overrides, publish, code in cases:
                with self.subTest(name=name):
                    output = root / "outputs"
                    output.write_text("")
                    env = os.environ | {
                        "PATH": str(root) + os.pathsep + os.environ["PATH"],
                        "SOURCE_SHA": "approved-sha",
                        "CI_RUN_NUMBER": "23",
                        "GITHUB_REPOSITORY": "test/impulsefy",
                        "GITHUB_OUTPUT": str(output),
                        "GH_TOKEN": "mock-only",
                    } | overrides
                    result = subprocess.run(
                        ["bash", "-euo", "pipefail", "-c", script],
                        env=env, capture_output=True, text=True,
                    )
                    self.assertEqual(result.returncode, code, result.stderr)
                    values = dict(line.split("=", 1) for line in output.read_text().splitlines())
                    self.assertEqual(values.get("publish") == "true", publish)
                    if publish:
                        self.assertEqual(values["name"], "0.1.23")
                        self.assertEqual(values["tag"], "v0.1.23")
                        self.assertEqual(values["code"], "24")


if __name__ == "__main__":
    unittest.main()
