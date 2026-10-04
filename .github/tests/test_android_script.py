"""Exercise the Android smoke script with fake build/ADB commands and no rg."""

import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import textwrap
import unittest


class AndroidSmokeScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="impulsefy-android-script-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        script = Path(__file__).resolve().parents[2] / "scripts/test-android.sh"
        (self.root / "scripts").mkdir()
        shutil.copyfile(script, self.root / "scripts/test-android.sh")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        # Only the portable script dependencies are available; never inherit rg.
        for command in ("bash", "dirname", "mkdir", "tee", "grep"):
            self.bin.joinpath(command).symlink_to(shutil.which(command))
        self.assertIsNone(shutil.which("rg", path=str(self.bin)))
        self.write_executable(self.root / "gradlew", """\
            import os, sys
            sys.exit(int(os.environ.get("MOCK_BUILD_EXIT", "0")))
            """)
        self.write_executable(self.bin / "adb", """\
            import os, sys
            args = sys.argv[1:]
            if args[:2] != ["-s", "emulator-5554"]:
                sys.exit("Unexpected device: " + repr(args))
            args = args[2:]
            if args[:2] == ["install", "-r"]:
                sys.exit(0)
            elif args == ["shell", "am", "instrument", "-w",
                          "com.impulsefy.test/com.impulsefy.SmokeInstrumentation"]:
                sys.stdout.write(os.environ["MOCK_OUTPUT"])
                sys.exit(int(os.environ.get("MOCK_INSTRUMENT_EXIT", "0")))
            elif args == ["exec-out", "run-as", "com.impulsefy", "cat",
                          "files/qr-smoke.png"]:
                sys.stdout.write("mock-qr")
            else:
                sys.exit("Unexpected ADB operation: " + repr(args))
            """)

    def write_executable(self, path, source):
        path.write_text(f"#!{sys.executable}\n" + textwrap.dedent(source))
        path.chmod(0o700)

    def run_script(self, output, **overrides):
        env = os.environ | {
            "PATH": str(self.bin),
            "ANDROID_HOME": str(self.root / "sdk"),
            "ANDROID_SERIAL": "emulator-5554",
            "MOCK_OUTPUT": output,
            "MOCK_BUILD_EXIT": "0",
            "MOCK_INSTRUMENT_EXIT": "0",
        } | overrides
        return subprocess.run(
            [str(self.bin / "bash"), "scripts/test-android.sh"],
            cwd=self.root, env=env, capture_output=True, text=True,
        )

    def test_pass_marker_succeeds_without_ripgrep(self):
        output = "Instrumentation output\nPASS: smoke checks\nINSTRUMENTATION_CODE: -1\n"
        result = self.run_script(output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.root / "artifacts/android-smoke.txt").read_text(), output)
        self.assertEqual((self.root / "artifacts/qr-smoke.png").read_text(), "mock-qr")

    def test_missing_or_invalid_pass_marker_fails(self):
        for output in ("", "FAIL: smoke checks\n", "prefix PASS: smoke checks\n",
                       " PASS: smoke checks\n", "pass: smoke checks\n", "PASS\n"):
            with self.subTest(output=output):
                result = self.run_script(output)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertFalse((self.root / "artifacts/qr-smoke.png").exists())

    def test_instrumentation_failure_is_not_hidden_by_pass_marker(self):
        result = self.run_script("PASS: smoke checks\n", MOCK_INSTRUMENT_EXIT="42")
        self.assertEqual(result.returncode, 42, result.stderr)
        self.assertFalse((self.root / "artifacts/qr-smoke.png").exists())

    def test_build_failure_stops_before_instrumentation(self):
        result = self.run_script("PASS: smoke checks\n", MOCK_BUILD_EXIT="23")
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertFalse((self.root / "artifacts").exists())

    def test_physical_device_is_rejected(self):
        result = self.run_script("PASS: smoke checks\n", ANDROID_SERIAL="physical-device")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn("Use um emulador de teste", result.stderr)
        self.assertFalse((self.root / "artifacts").exists())


if __name__ == "__main__":
    unittest.main()
