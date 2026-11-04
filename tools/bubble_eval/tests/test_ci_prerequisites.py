"""Execute the actual workflow gate against synthetic cache-presence cases."""

import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


@unittest.skipUnless(shutil.which("bash"), "The workflow prerequisite gate uses Bash")
class OfflineCiGateTests(unittest.TestCase):
    def test_missing_inputs_block_and_presence_is_not_a_provenance_claim(self):
        root = Path(__file__).resolve().parents[3]
        workflow = (root / ".github/workflows/android-validation.yml").read_text(encoding="utf-8")
        section = workflow.split("      - name: Gate on existing offline prerequisites\n", 1)[1]
        body = section.split("        run: |\n", 1)[1]
        lines = []
        for line in body.splitlines():
            if not line.startswith("          "):
                break
            lines.append(line[10:])
        self.assertTrue(lines, "The offline prerequisite gate must not be empty")
        for distribution, panel in ((False, False), (True, False), (False, True), (True, True)):
            with self.subTest(distribution=distribution, panel=panel):
                with tempfile.TemporaryDirectory() as temporary:
                    cache = Path(temporary)
                    if distribution:
                        executable = cache / "wrapper/dists/gradle-8.7-bin/test/gradle-8.7/bin/gradle"
                        executable.parent.mkdir(parents=True)
                        executable.write_bytes(b"")
                    if panel:
                        artifact = cache / "caches/modules-2/files-2.1/com.github.pedrovgs/deeppanel/0.0.1/test/deeppanel-0.0.1.aar"
                        artifact.parent.mkdir(parents=True)
                        artifact.write_bytes(b"")
                    environment = dict(os.environ, GRADLE_USER_HOME=str(cache))
                    result = subprocess.run(["bash", "-e", "-c", "\n".join(lines)],
                        env=environment, capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 0 if distribution and panel else 1)
                    self.assertIn("not established" if distribution and panel else "BLOCKED", result.stdout)


if __name__ == "__main__":
    unittest.main()
