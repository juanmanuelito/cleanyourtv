"""Every tools dependency is pinned to an exact version, so the tools' supply chain is reproducible
and auditable (see docs/superpowers/specs/2026-10-25-security-hardening-design.md)."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ_FILES = [
    ROOT / "tools" / "requirements.txt",
    ROOT / "tools" / "requirements-perf.txt",
]

# "name==1.2.3" only: no ranges (>=, ~=, <), no extras, no environment markers.
EXACT = re.compile(r"^[A-Za-z0-9_.-]+==[A-Za-z0-9_.+!-]+$")


class TestRequirementsPinned(unittest.TestCase):
    def test_every_requirement_is_exact_pinned(self):
        for req in REQ_FILES:
            lines = [l for l in req.read_text().splitlines()
                     if l.strip() and not l.lstrip().startswith("#")]
            self.assertTrue(lines, f"{req} is empty")
            for number, line in enumerate(lines, 1):
                self.assertRegex(
                    line, EXACT,
                    f"{req}:{number}: not an exact `==` pin (no ranges or markers allowed): {line!r}")

    def test_cryptography_is_pinned(self):
        text = (ROOT / "tools" / "requirements.txt").read_text()
        self.assertIn("cryptography==", text, "cryptography must be exact-pinned, not ranged")


if __name__ == "__main__":
    unittest.main()
