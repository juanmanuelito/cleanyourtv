# Security Hardening (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the fork's highest-value security gaps — release signing, dependency pinning, CI scanning, and documentation — without changing launcher behavior or the debloat tools.

**Architecture:** Four independent, shippable pieces: (1) exact-pin `tools/requirements.txt` and enforce it with a test, (2) a `security-scan` CI job (pip-audit + osv-scanner), (3) fork-owned release signing with graceful fallback to debug signing, (4) docs and a checksum helper. No launcher logic, no `disable.sh`/`restore.sh` changes.

**Tech Stack:** Python 3.12 (unittest), Gradle 8.9 / AGP 8.5.2 / JDK 17+ (Java 21 works locally), `keytool`, `pip-audit==2.10.1`, `osv-scanner==v2.7.0`, GitHub Actions.

**Fork-only note:** The `.github/workflows/ci.yml` change and the release-signing change are for *this fork only*. They must **not** be part of any upstream PR (Phase 4 contributes only the pinning and docs). See `AGENTS.md` "Don't edit `.github/workflows/`".

---

## Amendments (recorded during execution)

These reflect reality discovered while executing the plan; they supersede the corresponding steps below.

- **Second requirements file:** the repo also has `tools/requirements-perf.txt` (`perfetto==0.58.2`, `pillow==11.3.0`). The pin test (`tests/test_requirements_pinned.py`) now loops over **both** files, and the CI `security-scan` job audits both.
- **Vulnerable pins bumped:** the scan found real CVEs in the originally planned pins. Final pins are `cryptography==50.0.2` (was `46.0.7`) and `pillow==13.0.0` (was `11.3.0`). Verified `adb-shell==0.4.4` + `cryptography==50.0.2` still import.
- **APK cert verification:** `keytool -printcert -jarfile` fails on these APKs because AGP 8.5.2 with `minSdk 26` produces v2-only-signed APKs (pre-existing behavior). Verify the signer with `apksigner verify --print-certs` instead.
- **Checksum verification:** `sha256sums.txt` records the basename, so `sha256sum -c` must run from inside the release directory (`launcher/app/build/outputs/apk/release/`), not the repo root.

---

## File map

- Modify: `tools/requirements.txt` — exact pins.
- Create: `tests/test_requirements_pinned.py` — enforce exact pinning.
- Modify: `.github/workflows/ci.yml` — add `security-scan` job.
- Modify: `launcher/app/build.gradle` — optional release signing config with debug fallback.
- Create: `tools/new-keystore.sh` — generate keystore + `keystore.properties`.
- Modify: `.gitignore` — ignore keystore and properties.
- Create: `docs/SECURITY-SIGNING.md` — signing key doc.
- Modify: `README.md` — `WRITE_SECURE_SETTINGS` note (under `## The launcher`, line 545).
- Modify: `launcher/build-and-install.sh` — inline comment at the grant site.
- Create: `tools/make-release.sh` — build + `sha256sums.txt`.
- Modify: `SECURITY.md` — point to `tools/make-release.sh`.

---

## Task 1: Exact-pin Python dependencies

**Files:**
- Create: `tests/test_requirements_pinned.py`
- Modify: `tools/requirements.txt`

- [ ] **Step 1: Write the failing test**

Create `tests/test_requirements_pinned.py`:

```python
"""Every tools dependency is pinned to an exact version, so the tools' supply chain is reproducible
and auditable (see docs/superpowers/specs/2026-10-25-security-hardening-design.md)."""

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REQ = ROOT / "tools" / "requirements.txt"

# "name==1.2.3" only: no ranges (>=, ~=, <), no extras, no environment markers.
EXACT = re.compile(r"^[A-Za-z0-9_.-]+==[A-Za-z0-9_.+!-]+$")


class TestRequirementsPinned(unittest.TestCase):
    def test_every_requirement_is_exact_pinned(self):
        lines = [l for l in REQ.read_text().splitlines()
                 if l.strip() and not l.lstrip().startswith("#")]
        self.assertTrue(lines, f"{REQ} is empty")
        for number, line in enumerate(lines, 1):
            self.assertRegex(
                line, EXACT,
                f"{REQ}:{number}: not an exact `==` pin (no ranges or markers allowed): {line!r}")

    def test_cryptography_is_pinned(self):
        text = REQ.read_text()
        self.assertIn("cryptography==", text, "cryptography must be exact-pinned, not ranged")


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `python3 -m unittest discover -s tests -p test_requirements_pinned.py -v`

Expected: FAIL — `test_every_requirement_is_exact_pinned` reports `cryptography>=46.0.7` is "not an exact `==` pin".

- [ ] **Step 3: Pin cryptography**

Edit `tools/requirements.txt` so it reads:

```
adb-shell==0.4.4
rsa==4.9.1
cryptography==46.0.7
```

(`adb-shell` 0.4.4 and `rsa` 4.9.1 are already the latest on PyPI; `46.0.7` is the exact version the previous `>=46.0.7` floor meant, so this is a minimal, low-risk pin.)

- [ ] **Step 4: Run the test to verify it passes**

Run: `python3 -m unittest discover -s tests -p test_requirements_pinned.py -v`

Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add tools/requirements.txt tests/test_requirements_pinned.py
git commit -m "tools: exact-pin dependencies and test they stay pinned"
```

---

## Task 2: Dependency vulnerability scanning in CI

**Files:**
- Modify: `.github/workflows/ci.yml`

- [ ] **Step 1: Add the `security-scan` job**

Append this job to `.github/workflows/ci.yml` (after the `launcher` job, at the same indent level as the other jobs):

```yaml
  security-scan:
    # Fork-only: vulnerability scanning for the tools' Python deps and the repo.
    name: Dependency vulnerability scan
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - name: pip-audit on tools/requirements.txt
        run: |
          python -m pip install --upgrade pip-audit==2.10.1
          pip-audit -r tools/requirements.txt
      - name: osv-scanner on the repository
        run: |
          curl -sSfL https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_linux_amd64 -o osv-scanner
          chmod +x osv-scanner
          ./osv-scanner scan -r . -L tools/requirements.txt
```

Both tools exit non-zero when a known vulnerability is found, so the job fails on any hit.

- [ ] **Step 2: Verify the same commands pass locally**

Run from the repo root:

```bash
python3 -m venv /tmp/opencode/audit-venv
/tmp/opencode/audit-venv/bin/pip install -q pip-audit==2.10.1
/tmp/opencode/audit-venv/bin/pip-audit -r tools/requirements.txt
curl -sSfL https://github.com/google/osv-scanner/releases/download/v2.7.0/osv-scanner_linux_amd64 -o /tmp/opencode/osv-scanner && chmod +x /tmp/opencode/osv-scanner
/tmp/opencode/osv-scanner scan -r . -L tools/requirements.txt
```

Expected: `pip-audit` reports "No known vulnerabilities found"; `osv-scanner` exits 0 (no vulnerabilities). If `osv-scanner` reports the two pinned AndroidX deps, note them but do not change them (they are pinned in `launcher/app/build.gradle` and are not in `requirements.txt`).

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: scan Python and repo dependencies for vulnerabilities (fork-only)"
```

- [ ] **Step 4: Confirm CI passes after push**

Push the branch and confirm the new `security-scan` job is green on GitHub Actions (it will also run the existing `lists-and-tools`, `tables`, and `launcher` jobs; all must stay green).

---

## Task 3: Fork-owned release signing with debug fallback

**Files:**
- Modify: `launcher/app/build.gradle`
- Create: `tools/new-keystore.sh`
- Modify: `.gitignore`
- Create: `docs/SECURITY-SIGNING.md`

- [ ] **Step 1: Add the keystore loader and conditional signing config**

Edit `launcher/app/build.gradle`. After the `plugins { ... }` block (before `android {`), insert:

```groovy
// Fork-only release signing: load an optional keystore.properties so the release APK is signed with
// this fork's own key instead of the public Android debug key. The file is gitignored; without it the
// build falls back to debug signing, so the repo still builds anywhere (upstream, CI without a key).
def keystoreProperties = new Properties()
def keystorePropertiesFile = rootProject.file('keystore.properties')
def hasReleaseKeystore = keystorePropertiesFile.exists()
if (hasReleaseKeystore) {
    keystorePropertiesFile.withInputStream { keystoreProperties.load(it) }
}
```

Inside `android { ... }`, right after `compileSdk 34`, add:

```groovy
    signingConfigs {
        if (hasReleaseKeystore) {
            release {
                storeFile rootProject.file(keystoreProperties['storeFile'])
                storePassword keystoreProperties['storePassword']
                keyAlias keystoreProperties['keyAlias']
                keyPassword keystoreProperties['keyPassword']
            }
        }
    }
```

In `buildTypes.release`, change the line:

```groovy
            signingConfig signingConfigs.debug
```

to:

```groovy
            signingConfig hasReleaseKeystore ? signingConfigs.release : signingConfigs.debug
```

- [ ] **Step 2: Add the keystore generator**

Create `tools/new-keystore.sh`:

```bash
#!/usr/bin/env bash
# Generate this fork's release signing keystore and keystore.properties, once, out of git.
# Run from the repo root. The keystore and properties file are the only copy: back them up.
# Losing them means a full uninstall/reinstall of the launcher (see docs/SECURITY-SIGNING.md).
set -euo pipefail
cd "$(dirname "$0")/.."
KS=launcher/cleanyourtv-release.keystore
PROPS=launcher/keystore.properties
if [ -e "$KS" ] || [ -e "$PROPS" ]; then
  echo "refusing: $KS or $PROPS already exists (delete them deliberately to rotate the key)" >&2
  exit 1
fi
command -v keytool >/dev/null 2>&1 || { echo "keytool not found: install a JDK" >&2; exit 1; }
PASS="$(head -c 32 /dev/urandom | base64 | tr -d '\n/+=' | cut -c1-32)"
ALIAS=cleanyourtv
keytool -genkeypair -v \
  -keystore "$KS" -storepass "$PASS" -keypass "$PASS" \
  -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=cleanyourtv, OU=cleanyourtv, O=cleanyourtv, C=US" >/dev/null
cat > "$PROPS" <<EOF
storeFile=cleanyourtv-release.keystore
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF
chmod 600 "$KS" "$PROPS"
echo "wrote $KS and $PROPS (both gitignored). Back them up now; losing them means a full launcher reinstall."
```

Make it executable: `chmod +x tools/new-keystore.sh`.

- [ ] **Step 3: Ignore the keystore and properties**

Append to `.gitignore` (after the `launcher/local.properties` line):

```
# Fork release signing key (generated by tools/new-keystore.sh); never commit the key or its password.
*.keystore
*.jks
keystore.properties
```

- [ ] **Step 4: Write the signing doc**

Create `docs/SECURITY-SIGNING.md`:

```markdown
# Signing the release APK

The release APK is normally signed with Android's public debug key, so any machine with an Android
SDK can build an APK that installs right over it. This fork instead signs with a key you own.

Generate it once (from the repo root):

    tools/new-keystore.sh

This writes two gitignored files in `launcher/`:

- `cleanyourtv-release.keystore` — the signing key.
- `keystore.properties` — the alias and passwords Gradle reads at build time.

**They are the only copy.** Back them up. If you lose them you cannot update an installed launcher;
you must uninstall it (`adb uninstall com.gathin.tvhome`) and install a freshly signed build.

`launcher/app/build.gradle` reads `keystore.properties` when it is present and falls back to debug
signing when it is not, so anyone (upstream contributors, CI without the key) can still build.

To rotate the key: delete both files, run `tools/new-keystore.sh`, and reinstall the launcher once on
each TV.

The key never leaves `launcher/` and is listed in `.gitignore`; do not commit either file.
```

- [ ] **Step 5: Build with the keystore present and verify the cert**

```bash
tools/new-keystore.sh          # creates launcher/cleanyourtv-release.keystore + launcher/keystore.properties
cd launcher && ./gradlew --no-daemon assembleRelease -q --console=plain
cd ..
keytool -printcert -jarfile launcher/app/build/outputs/apk/release/app-release.apk
```

Expected: the build succeeds and `keytool -printcert` shows `Owner: CN=cleanyourtv, OU=cleanyourtv, O=cleanyourtv, C=US` (NOT `CN=Android Debug, O=Android, C=US`). `ls -l launcher/cleanyourtv-release.keystore launcher/keystore.properties` shows `-rw-------` (0600).

- [ ] **Step 6: Verify the debug fallback still works**

```bash
mv launcher/keystore.properties /tmp/opencode/keystore.properties.bak
cd launcher && ./gradlew --no-daemon assembleRelease -q --console=plain && cd ..
keytool -printcert -jarfile launcher/app/build/outputs/apk/release/app-release.apk
mv /tmp/opencode/keystore.properties.bak launcher/keystore.properties
```

Expected: the build still succeeds and the cert is `CN=Android Debug` (fallback intact). After restoring `keystore.properties`, rebuild once more so the checked-out tree's APK is release-signed again (optional; the APK is gitignored anyway).

- [ ] **Step 7: Confirm nothing was committed**

Run: `git status --porcelain`

Expected: `launcher/cleanyourtv-release.keystore` and `launcher/keystore.properties` do NOT appear (they are ignored); the modified `build.gradle`, `.gitignore`, `tools/new-keystore.sh`, and `docs/SECURITY-SIGNING.md` do.

- [ ] **Step 8: Commit**

```bash
git add launcher/app/build.gradle tools/new-keystore.sh .gitignore docs/SECURITY-SIGNING.md
git commit -m "Launcher: fork-owned release signing with debug fallback"
```

---

## Task 4: Document the WRITE_SECURE_SETTINGS grant

**Files:**
- Modify: `launcher/build-and-install.sh`
- Modify: `README.md`

- [ ] **Step 1: Extend the inline comment at the grant site**

In `launcher/build-and-install.sh`, the `RESTORE_KEY_SERVICES` block (around the `pm grant ... WRITE_SECURE_SETTINGS` line) currently reads:

```bash
if [ "${RESTORE_KEY_SERVICES:-0}" = 1 ]; then
  "$PY" -I ../tools/tv.py "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS && echo restore-granted"
fi
```

Replace it with:

```bash
if [ "${RESTORE_KEY_SERVICES:-0}" = 1 ]; then
  # WRITE_SECURE_SETTINGS is a powerful permission: it lets the app change system settings broadly.
  # It is granted only here, only on request, so ServiceRepair can put the key helpers back after a
  # force-stop of the launcher. On a TV you do not fully control, prefer leaving it off.
  "$PY" -I ../tools/tv.py "pm grant com.gathin.tvhome android.permission.WRITE_SECURE_SETTINGS && echo restore-granted"
fi
```

- [ ] **Step 2: Add a README note**

In `README.md`, immediately after the "Build it yourself" `</details>` block that ends around line 575 (inside `## The launcher`), insert:

```markdown
<details>
<summary><b>Security notes</b></summary>

- **Release signing:** this fork signs the release APK with its own key (`tools/new-keystore.sh`),
  never the public Android debug key. See [`docs/SECURITY-SIGNING.md`](docs/SECURITY-SIGNING.md).
- **`RESTORE_KEY_SERVICES=1`** grants the launcher `android.permission.WRITE_SECURE_SETTINGS` so it
  can put its accessibility key helpers back after a force-stop. It is off by default; pass it only
  on a TV you own and trust the launcher on, since that permission can change system settings.

</details>
```

- [ ] **Step 3: Verify the note renders and no generated tables changed**

Run: `git diff --stat`

Expected: only `README.md` and `launcher/build-and-install.sh` changed, and only in prose/comment areas (no table rows, no counts).

- [ ] **Step 4: Commit**

```bash
git add README.md launcher/build-and-install.sh
git commit -m "docs: explain the WRITE_SECURE_SETTINGS grant and fork release signing"
```

---

## Task 5: Release checksums and SECURITY.md pointer

**Files:**
- Create: `tools/make-release.sh`
- Modify: `SECURITY.md`

- [ ] **Step 1: Add the checksum helper**

Create `tools/make-release.sh`:

```bash
#!/usr/bin/env bash
# Build the release APK and write its SHA-256 next to it, for release verification.
# See SECURITY.md and docs/SECURITY-SIGNING.md.
set -euo pipefail
cd "$(dirname "$0")/../launcher"
./gradlew --no-daemon assembleRelease -q --console=plain
APK=app/build/outputs/apk/release/app-release.apk
SUMS=app/build/outputs/apk/release/sha256sums.txt
( cd "$(dirname "$APK")" && sha256sum "$(basename "$APK")" > sha256sums.txt )
cat "$SUMS"
echo "checksums written to $SUMS"
```

Make it executable: `chmod +x tools/make-release.sh`.

- [ ] **Step 2: Add a pointer in SECURITY.md**

In `SECURITY.md`, after the `## How to report` section's closing line and before `## What counts`, insert:

```markdown
## Verifying a release

A built release APK is verified against its checksum: `tools/make-release.sh` builds the APK and
writes `sha256sums.txt` beside it, so `sha256sum -c` confirms a download is the artifact that came
out of this repository.
```

- [ ] **Step 3: Run the helper and confirm output**

```bash
tools/make-release.sh
sha256sum -c launcher/app/build/outputs/apk/release/sha256sums.txt
```

Expected: the build succeeds, `make-release.sh` prints the SHA-256, and `sha256sum -c` answers `app-release.apk: OK`. (Note: the APK is gitignored, so no artifact is committed.)

- [ ] **Step 4: Commit**

```bash
git add tools/make-release.sh SECURITY.md
git commit -m "tools: make-release.sh writes checksums; SECURITY.md points to it"
```

---

## Final verification

- [ ] Run the full test suite as CI does:

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
python3 -m unittest discover -s tests -v
```

Expected: all tests pass, including the new `test_requirements_pinned.py`.

- [ ] Confirm the launcher still builds and passes lint (JDK 17+, SDK 34):

```bash
cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease
```

Expected: lint zero errors, build succeeds, APK under 589,824 B (576 KB): `stat -c %s launcher/app/build/outputs/apk/release/app-release.apk`.

- [ ] Push the branch and confirm all four CI jobs are green (`lists-and-tools`, `tables`, `launcher`, `security-scan`).

- [ ] `git status` is clean except for the gitignored keystore files (which must never be committed).
