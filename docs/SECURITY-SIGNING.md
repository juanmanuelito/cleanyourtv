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
