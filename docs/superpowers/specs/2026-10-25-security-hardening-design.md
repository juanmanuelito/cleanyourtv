# Security Hardening — Phase 1

Date: 2026-10-25
Status: approved (signing option A)

## Goal

Close the highest-value, lowest-risk security gaps in this fork before it is used against real
hardware (Phase 2 targets the owner's Mi TV MOOQ0). Everything here must stay backward-compatible
with upstream so the uncontroversial parts can be upstreamed later (Phase 4).

## Scope

1. Replace debug signing with a per-fork release keystore (option A).
2. Pin Python dependencies and add dependency scanning to CI.
3. Document the `WRITE_SECURE_SETTINGS` grant surface.
4. Add release checksums and a `SECURITY.md` pointer.

Out of scope (deferred to later phases): narrowing the `WRITE_SECURE_SETTINGS` self-repair path,
new launcher/tool features, MOOQ0 recipe changes, upstream PRs.

## 1. APK signing (option A)

### Problem

`launcher/app/build.gradle` signs the release build with `signingConfigs.debug` — the public
`androiddebugkey` that ships with every Android SDK. Anyone can build a malicious APK signed with the
same key and `pm install -r` it over the installed launcher.

### Design

- Add a release keystore owned by the fork owner, kept **out of git**.
- Load signing parameters from a gitignored `launcher/keystore.properties`, so no secret or password
  ever enters the repository.
- **Graceful fallback:** when `keystore.properties` is absent, fall back to the current debug signing.
  This keeps the repo buildable by anyone (upstream contributors, CI without the secret) while this
  fork's builds use the release key whenever the file exists.

### Changes

- `launcher/app/build.gradle`:
  - Add a `signingConfigs.release` block populated from `keystore.properties` (via a small helper in
    Gradle, reading `storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
  - In `buildTypes.release`, use `signingConfig signingConfigs.release` when the properties are
    present, otherwise keep `signingConfig signingConfigs.debug`.
- New `tools/new-keystore.sh`: generates `launcher/cleanyourtv-release.keystore` with `keytool`
  (RSA 4096, validity 10000 days) and writes `launcher/keystore.properties`. Prints a reminder that
  the keystore + properties file are the only copy and must be backed up.
- `.gitignore`: add `*.keystore`, `*.jks`, `keystore.properties`.
- `docs/SECURITY-SIGNING.md` (short): how the key is generated, where it lives, that losing it means
  a full uninstall/reinstall of the launcher, and how to rotate it.

### Compatibility note

`build-and-install.sh` needs no change: it already runs `assembleRelease`, and Gradle selects the
signing config. A device that previously had the debug-signed launcher must uninstall it before the
release-signed APK installs (signature mismatch). The owner has not installed the launcher yet, so
there is no migration cost. Document this one-time caveat in `SECURITY-SIGNING.md`.

## 2. Dependency pinning and scanning

### Problem

`tools/requirements.txt` pins `adb-shell==0.4.4` and `rsa==4.9.1` but floats `cryptography>=46.0.7`.
CI has no vulnerability scanning of either the Python or the Gradle/AndroidX dependencies.

### Design

- Pin `cryptography` to an exact version. The concrete version is resolved at implementation time by
  checking what actually installs in the `tools/venv` (`pip index versions cryptography` / a fresh
  `pip install`), then recorded exactly. Same for a verified `adb-shell` bump if a safe newer
  release exists (otherwise leave it pinned and note why).
- Add a CI job (`.github/workflows/ci.yml`, new job `security-scan`) that runs:
  - `pip-audit -r tools/requirements.txt` (fails on any known vulnerability).
  - `osv-scanner` over the repository (fails on vulnerabilities in the Gradle dependency graph and
    any committed artifacts).

### Changes

- `tools/requirements.txt`: exact pins.
- `.github/workflows/ci.yml`: add the `security-scan` job (install `pip-audit` from pip and
  `osv-scanner` from the official installer, `contents: read` permission, cache where cheap).
- `README.md` (optional, one line): note the security scan exists.

## 3. `WRITE_SECURE_SETTINGS` documentation

### Problem

`launcher/build-and-install.sh` grants `android.permission.WRITE_SECURE_SETTINGS` to the launcher
when `RESTORE_KEY_SERVICES=1`. That permission lets an app change system settings broadly. It is
off by default, but the risk is not explained.

### Design

No code change in Phase 1. Add:

- An inline comment at the grant site explaining exactly why it is granted (the `ServiceRepair`
  self-repair of accessibility services after a force-stop) and the trade.
- A README subsection under "The launcher" documenting when `RESTORE_KEY_SERVICES=1` should be used
  and what it exposes.

Narrowing the grant to a smaller self-repair path is deferred to Phase 3.

## 4. Release checksums

### Design

- New `tools/make-release.sh`: builds the release APK (`./gradlew assembleRelease`), then writes
  `launcher/app/build/outputs/apk/release/sha256sums.txt` containing the SHA-256 of
  `app-release.apk`, and prints it.
- `SECURITY.md`: add a line pointing to `tools/make-release.sh` so release artifacts can be verified.

## Testing / verification

- `security-scan` job passes in CI on this repo (and fails with a deliberately-vulnerable pin, to
  prove it is not a no-op).
- `tools/new-keystore.sh` run twice on a clean checkout: first run creates the keystore + properties;
  second run refuses to overwrite an existing keystore (idempotency/safety).
- With `keystore.properties` present, `assembleRelease` produces an APK signed with the release key
  (`apksigner verify --print-certs` shows the fork's cert, not `CN=Android Debug`).
- With `keystore.properties` absent, `assembleRelease` still produces the debug-signed APK (fallback
  intact), and CI stays green.
- `pip-audit` and `osv-scanner` run clean after pinning.

## Non-goals

- No launcher behavior changes, no new features, no MOOQ0 recipe edits.
- No changes to `disable.sh`/`restore.sh`/`recovery.py` logic.
