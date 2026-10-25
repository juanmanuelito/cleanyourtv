# On-TV Theme Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Browse and apply the repo's themes from the launcher's Settings on the TV, by pushing all themes into the launcher's data and adding a Themes picker that copies the chosen theme into the existing overlay.

**Architecture:** A push tool (`tools/theme-catalog.sh`) writes every `themes/<name>/` into the launcher's external files dir. A new `ThemeCatalog` class enumerates and applies them by copying files into the single-theme overlay (`Config`'s existing read path). `LauncherSettingsActivity` gains a Themes row (shown only when the catalog is present) that opens a thumbnail picker; applying bumps the existing `settings_revision` so Home recreates on resume.

**Tech Stack:** Plain Java (Android launcher, no new deps), a bash tool mirroring `tools/theme.sh`, JVM unit tests (`app/src/test`), Python test suite (`tests/`).

**Spec:** `docs/superpowers/specs/2026-10-25-on-tv-theme-catalog-design.md`.

**Key facts (verified):** `Config.dir(ctx) = ctx.getExternalFilesDir(null)`; `Config.FILE_NAME = "config.json"`; `Config.WALLPAPER_NAME = "wallpaper.jpg"`; theme revision file is `theme_revision`; Home rebuilds on resume when `Config.SETTINGS_REVISION_KEY` ("settings_revision") changed (`MainActivity.onResume` line 927, bumped in `LauncherSettingsActivity` lines 261-262). `ThemeProvider`/`ThemeFiles` whitelist currently only allows `config.json`, `wallpaper.jpg`, `theme_revision`, and `wallpapers/<image>` — it must be extended for `themes/<name>/...`.

---

## File map

- Modify: `launcher/app/src/main/java/com/gathin/tvhome/ThemeFiles.java` — allow `themes/<name>/...` paths (security whitelist).
- Create: `launcher/app/src/main/java/com/gathin/tvhome/ThemeCatalog.java` — enumerate + apply + clear.
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/LauncherSettingsActivity.java` — Themes row + picker.
- Modify: `launcher/app/src/main/res/values/strings.xml` — new labels.
- Modify: `launcher/app/src/test/java/com/gathin/tvhome/ThemeFilesTest.java` — extend tests.
- Create: `launcher/app/src/test/java/com/gathin/tvhome/ThemeCatalogTest.java` — enumerate/apply tests.
- Create: `tools/theme-catalog.sh` — push all themes to the TV.
- Modify: `README.md` — Tools table row for `theme-catalog.sh`.

---

## Task 1: Extend the ThemeFiles whitelist for the catalog

**Files:**
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/ThemeFiles.java`
- Modify: `launcher/app/src/test/java/com/gathin/tvhome/ThemeFilesTest.java`

- [ ] **Step 1: Write the failing test**

Append these cases to `ThemeFilesTest` (open it first; it already tests `ThemeFiles.path` and `isCollection` with `List.of(...)`):

```java
    @Test public void catalogConfig() {
        assertEquals("themes/midnight/config.json", ThemeFiles.path(List.of("themes", "midnight", "config.json")));
    }
    @Test public void catalogWallpaper() {
        assertEquals("themes/midnight/wallpaper.jpg", ThemeFiles.path(List.of("themes", "midnight", "wallpaper.jpg")));
    }
    @Test public void catalogScreenshot() {
        assertEquals("themes/midnight/screenshot.jpg", ThemeFiles.path(List.of("themes", "midnight", "screenshot.jpg")));
    }
    @Test public void catalogCollectionImage() {
        assertEquals("themes/midnight/wallpapers/dusk.jpg", ThemeFiles.path(List.of("themes", "midnight", "wallpapers", "dusk.jpg")));
    }
    @Test public void catalogCollectionCaptions() {
        assertEquals("themes/midnight/wallpapers/captions.txt", ThemeFiles.path(List.of("themes", "midnight", "wallpapers", "captions.txt")));
    }
    @Test public void catalogRejectsTraversal() {
        assertNull(ThemeFiles.path(List.of("themes", "..", "config.json")));
        assertNull(ThemeFiles.path(List.of("themes", "a/b", "config.json")));
        assertNull(ThemeFiles.path(List.of("themes", "midnight", "evil.exe")));
    }
    @Test public void catalogIsCollection() {
        assertTrue(ThemeFiles.isCollection(List.of("themes", "midnight", "wallpapers")));
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest --tests "com.gathin.tvhome.ThemeFilesTest"`

Expected: FAIL — the new `catalog*` assertions fail (path returns null today).

- [ ] **Step 3: Extend ThemeFiles.path and isCollection**

Edit `ThemeFiles.java`. Replace the `IMAGE` pattern line and the `path`/`isCollection` methods with:

```java
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);
    /** A theme name in the catalog: no slashes, no dots-as-parents (rejects "..", ".", leading/trailing dots),
        so it can never leave the theme dir. */
    private static final Pattern THEME_NAME = Pattern.compile("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*");

    private ThemeFiles() {}

    /** The path relative to the theme directory, e.g. "config.json" or "wallpapers/dusk.jpg", or null if refused. */
    static String path(List<String> segments) {
        if (segments == null || segments.isEmpty()) return null;
        if (segments.size() == 1) {
            String name = segments.get(0);
            return Config.FILE_NAME.equals(name) || "wallpaper.jpg".equals(name) || "theme_revision".equals(name)
                    ? name : null;
        }
        if (segments.size() == 2 && WALLPAPERS.equals(segments.get(0))
                && (collectionImage(segments.get(1)) || WallpaperCaptions.FILE_NAME.equals(segments.get(1)))) {
            return WALLPAPERS + "/" + segments.get(1);
        }
        // themes/<name>/<rest>: the catalog written by tools/theme-catalog.sh. The name is whitelisted, so
        // a name like ".." or "a/b" is refused and cannot escape the theme directory.
        if (segments.size() >= 3 && "themes".equals(segments.get(0))
                && THEME_NAME.matcher(segments.get(1)).matches()) {
            String tail = path(segments.subList(2, segments.size()));
            if (tail != null) return "themes/" + segments.get(1) + "/" + tail;
            if (segments.size() == 3 && "screenshot.jpg".equals(segments.get(2))) {
                return "themes/" + segments.get(1) + "/screenshot.jpg";
            }
        }
        return null;
    }

    /** True for "wallpapers" alone: a delete of it clears the collection's images. (The catalog itself is
        cleared by the shell `rm -rf themes`, not through the provider's collection delete.) */
    static boolean isCollection(List<String> segments) {
        return segments != null && segments.size() == 1 && WALLPAPERS.equals(segments.get(0));
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest --tests "com.gathin.tvhome.ThemeFilesTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/ThemeFiles.java launcher/app/src/test/java/com/gathin/tvhome/ThemeFilesTest.java
git commit -m "Launcher: ThemeFiles allows themes/<name>/ catalog paths"
```

---

## Task 2: ThemeCatalog scanner

**Files:**
- Create: `launcher/app/src/main/java/com/gathin/tvhome/ThemeCatalog.java`
- Create: `launcher/app/src/test/java/com/gathin/tvhome/ThemeCatalogTest.java`

- [ ] **Step 1: Write the failing test**

Create `launcher/app/src/test/java/com/gathin/tvhome/ThemeCatalogTest.java`:

```java
package com.gathin.tvhome;

import org.junit.Test;
import java.io.File;
import java.io.FileWriter;
import java.util.List;
import static org.junit.Assert.*;

public class ThemeCatalogTest {

    @Test public void listSkipsFoldersWithoutConfig() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "cyt-cat-" + System.nanoTime());
        File cat = new File(dir, "themes");
        assertTrue(new File(cat, "good").mkdirs());
        assertTrue(new File(cat, "bad").mkdirs());
        try (FileWriter w = new FileWriter(new File(cat, "good/config.json"))) { w.write("{}"); }
        List<ThemeCatalog.Entry> entries = ThemeCatalog.list(dir);
        assertEquals(1, entries.size());
        assertEquals("good", entries.get(0).name);
        assertNull(entries.get(0).thumbnail);
    }

    @Test public void emptyWhenNoCatalog() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "cyt-nocat-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        assertTrue(ThemeCatalog.list(dir).isEmpty());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest --tests "com.gathin.tvhome.ThemeCatalogTest"`

Expected: FAIL — `ThemeCatalog` does not exist.

- [ ] **Step 3: Implement ThemeCatalog.list**

Create `launcher/app/src/main/java/com/gathin/tvhome/ThemeCatalog.java`:

```java
package com.gathin.tvhome;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The on-TV theme catalog: folders under Config.dir/themes, pushed by tools/theme-catalog.sh. */
final class ThemeCatalog {
    static final String CATALOG = "themes";

    /** One catalog theme: its folder name and its screenshot.jpg thumbnail, or null when it has none. */
    static final class Entry {
        final String name;
        final File thumbnail;
        Entry(String name, File thumbnail) { this.name = name; this.thumbnail = thumbnail; }
    }

    private ThemeCatalog() {}

    /** The catalog, in folder order; a folder without config.json is skipped. Empty when there is no catalog. */
    static List<Entry> list(File dir) {
        if (dir == null) return Collections.emptyList();
        File cat = new File(dir, CATALOG);
        File[] folders = cat.isDirectory() ? cat.listFiles(File::isDirectory) : null;
        if (folders == null) return Collections.emptyList();
        List<Entry> out = new ArrayList<>();
        for (File f : folders) {
            if (!new File(f, Config.FILE_NAME).isFile()) continue;
            File shot = new File(f, "screenshot.jpg");
            out.add(new Entry(f.getName(), shot.isFile() ? shot : null));
        }
        return out;
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest --tests "com.gathin.tvhome.ThemeCatalogTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/ThemeCatalog.java launcher/app/src/test/java/com/gathin/tvhome/ThemeCatalogTest.java
git commit -m "Launcher: ThemeCatalog enumerates pushed themes"
```

---

## Task 3: ThemeCatalog apply and clear (copy into overlay)

**Files:**
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/ThemeCatalog.java`
- Modify: `launcher/app/src/test/java/com/gathin/tvhome/ThemeCatalogTest.java`

- [ ] **Step 1: Write the failing test**

Append to `ThemeCatalogTest`:

```java
    @Test public void applyCopiesConfigAndWallpapers() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "cyt-apply-" + System.nanoTime());
        File theme = new File(dir, "themes/midnight");
        assertTrue(new File(theme, "wallpapers").mkdirs());
        write(new File(theme, "config.json"), "{\"background_color\":\"#05070F\"}");
        write(new File(theme, "wallpapers/dusk.jpg"), "img");
        assertTrue(ThemeCatalog.apply(dir, "midnight"));
        assertEquals("{\"background_color\":\"#05070F\"}", read(new File(dir, "config.json")));
        assertTrue(new File(dir, "wallpapers/dusk.jpg").isFile());
        assertTrue(new File(dir, "theme_revision").isFile());
    }

    @Test public void applyRefusesUnknownName() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "cyt-refuse-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        assertFalse(ThemeCatalog.apply(dir, "../evil"));
        assertFalse(ThemeCatalog.apply(dir, "missing"));
    }

    @Test public void clearRemovesOverlay() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "cyt-clear-" + System.nanoTime());
        assertTrue(new File(dir, "wallpapers").mkdirs());
        write(new File(dir, "config.json"), "{}");
        write(new File(dir, "wallpapers/a.jpg"), "img");
        assertTrue(ThemeCatalog.clear(dir));
        assertFalse(new File(dir, "config.json").exists());
        assertFalse(new File(dir, "wallpapers/a.jpg").exists());
    }

    private static void write(File f, String s) {
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write(s); }
        catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
    private static String read(File f) {
        try (java.io.FileReader r = new java.io.FileReader(f)) {
            StringBuilder b = new StringBuilder(); int c;
            while ((c = r.read()) != -1) b.append((char) c);
            return b.toString();
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest --tests "com.gathin.tvhome.ThemeCatalogTest"`

Expected: FAIL — `apply`/`clear` do not exist.

- [ ] **Step 3: Implement apply and clear**

Add these methods to `ThemeCatalog.java` (import `java.util.UUID` and reuse the same name guard as `ThemeFiles`):

```java
    /** Copy one catalog theme into the single-theme overlay Config reads. Returns false when the name is
     *  refused or the theme has no config.json. Wallpapers are written first, config.json last, so a failed
     *  copy never leaves the new config pointing at a missing picture. */
    static boolean apply(File dir, String name) {
        if (dir == null || name == null || !name.matches("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) return false;
        File theme = new File(new File(dir, CATALOG), name);
        if (!new File(theme, Config.FILE_NAME).isFile()) return false;
        File destWallpapers = new File(dir, "wallpapers");
        if (!destWallpapers.exists() && !destWallpapers.mkdirs()) return false;
        File[] old = destWallpapers.listFiles();
        if (old != null) for (File f : old) f.delete();
        File single = new File(theme, Config.WALLPAPER_NAME);
        File destSingle = new File(dir, Config.WALLPAPER_NAME);
        destSingle.delete();
        if (single.isFile()) copy(single, destSingle);
        File srcWallpapers = new File(theme, "wallpapers");
        if (srcWallpapers.isDirectory()) {
            File[] imgs = srcWallpapers.listFiles();
            if (imgs != null) for (File f : imgs) if (f.isFile()) copy(f, new File(destWallpapers, f.getName()));
        }
        copy(new File(theme, Config.FILE_NAME), new File(dir, Config.FILE_NAME));
        try (java.io.FileWriter w = new java.io.FileWriter(new File(dir, "theme_revision"))) {
            w.write(UUID.randomUUID().toString());
        } catch (java.io.IOException e) { return false; }
        return true;
    }

    /** Remove the overlay, back to config.xml. */
    static boolean clear(File dir) {
        if (dir == null) return false;
        new File(dir, Config.FILE_NAME).delete();
        new File(dir, Config.WALLPAPER_NAME).delete();
        File wallpapers = new File(dir, "wallpapers");
        File[] files = wallpapers.listFiles();
        if (files != null) for (File f : files) f.delete();
        try (java.io.FileWriter w = new java.io.FileWriter(new File(dir, "theme_revision"))) {
            w.write(UUID.randomUUID().toString());
        } catch (java.io.IOException e) { return false; }
        return true;
    }

    private static void copy(File from, File to) {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (java.io.IOException ignored) { }
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest --tests "com.gathin.tvhome.ThemeCatalogTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/ThemeCatalog.java launcher/app/src/test/java/com/gathin/tvhome/ThemeCatalogTest.java
git commit -m "Launcher: apply and clear a catalog theme into the overlay"
```

---

## Task 4: Themes row + picker in Launcher settings

**Files:**
- Modify: `launcher/app/src/main/res/values/strings.xml`
- Modify: `launcher/app/src/main/java/com/gathin/tvhome/LauncherSettingsActivity.java`

- [ ] **Step 1: Add string resources**

Append to `launcher/app/src/main/res/values/strings.xml` (inside `<resources>`):

```xml
    <string name="themes">Themes</string>
    <string name="theme_builtin">Built-in</string>
    <string name="theme_applied">Theme applied</string>
```

- [ ] **Step 2: Wire the Themes row**

In `LauncherSettingsActivity`, add a Themes row next to the existing wallpaper row (`wallpaperChoice`). After the `wallpaperChoice.setOnClickListener(v -> showWallpaperPicker());` block (line ~103), add:

```java
        if (!ThemeCatalog.list(Config.dir(this)).isEmpty()) {
            addRow(getString(R.string.themes), this::showThemePicker);
        }
```

Use the same `addRow`/row helper the file already uses for the wallpaper row (check its exact signature; the wallpaper row is built with `wallpaperChoice`, so mirror that). If `addRow` does not exist by that name, follow the existing wallpaper-row construction: a `TextView` row that opens the picker on click.

- [ ] **Step 3: Implement the picker**

Add a `showThemePicker()` method that opens a thumbnail list. Model it on `showWallpaperPicker()` (line 163): it already builds a `RecyclerView` of `WallpaperRotation.images(...)`. Instead, build the list from `ThemeCatalog.list(Config.dir(this))` (one row per `Entry`, plus a first "Built-in" row), showing `entry.name` and, when `entry.thumbnail != null`, its decoded bitmap (reuse the wallpaper decode helper `WallpaperRotation`/`WallpaperPlayer` already used by the wallpaper picker; a null thumbnail shows a neutral tile). On click:

```java
        boolean ok = name == null
                ? ThemeCatalog.clear(Config.dir(this))
                : ThemeCatalog.apply(Config.dir(this), name);
        if (ok) {
            bumpRevision();
            Toast.makeText(this, R.string.theme_applied, Toast.LENGTH_SHORT).show();
            finish();
        }
```

`bumpRevision()` is the existing settings-revision bump (lines 261-262):

```java
        SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        choices.edit().putLong(Config.SETTINGS_REVISION_KEY,
                choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
```

(`MainActivity.onResume` already recreates Home when that count changes, so Home picks up the new overlay.)

- [ ] **Step 4: Build and lint**

Run: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease`

Expected: tests pass, lint zero errors, APK produced. Confirm the APK is under 589,824 B: `stat -c %s launcher/app/build/outputs/apk/release/app-release.apk`.

- [ ] **Step 5: Commit**

```bash
git add launcher/app/src/main/java/com/gathin/tvhome/LauncherSettingsActivity.java launcher/app/src/main/res/values/strings.xml
git commit -m "Launcher: Themes row picks a catalog theme into the overlay"
```

---

## Task 5: The push tool

**Files:**
- Create: `tools/theme-catalog.sh`
- Modify: `README.md`

- [ ] **Step 1: Create tools/theme-catalog.sh**

Create the script by copying `tools/theme.sh`'s provider/staging helpers but pushing each `themes/*/` folder under a `themes/<name>/` prefix. Full content:

```bash
#!/usr/bin/env bash
# Push every theme in themes/ to the launcher's on-TV catalog, as files/themes/<name>/, and restart Home
# so the Themes row lists them. Mirrors tools/theme.sh: stage in /data/local/tmp, write through
# ThemeProvider (content://com.gathin.tvhome.theme) with a cp fallback.
# Usage: TV_HOST=192.168.1.50 tools/theme-catalog.sh [themes-dir]      (default: themes)
#        TV_HOST=192.168.1.50 tools/theme-catalog.sh --clear
set -euo pipefail
cd "$(dirname "$0")"
: "${TV_HOST:?set TV_HOST to the TV IP address, e.g. TV_HOST=192.168.1.50 $0}"
PY=venv/bin/python
[ -x "$PY" ] || PY=venv/Scripts/python.exe
[ -x "$PY" ] || { echo "run: python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt" >&2; exit 1; }
export MSYS_NO_PATHCONV=1
PKG=com.gathin.tvhome
DIR=/sdcard/Android/data/$PKG/files
URI=content://$PKG.theme
TMP=/data/local/tmp/cyt-catalog
stage() { echo "$TMP-${1//\//-}"; }
put() { echo "rm -f $DIR/$1 2>/dev/null; { o=\$(content write --uri $URI/$1 < $(stage "$1") 2>&1) && case \"\$o\" in *'Unsupported operation'*) false ;; esac; } || cp $(stage "$1") $DIR/$1 2>/dev/null; rm -f $(stage "$1")"; }
tv() { "$PY" -I tv.py "$@"; }
RESTART="a=\$(settings get secure enabled_accessibility_services); am force-stop $PKG; case \"\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1)\" in $PKG/*) am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null ;; *) am start -n $PKG/.MainActivity >/dev/null ;; esac; case \"\$a\" in *$PKG*) sleep 1; settings put secure enabled_accessibility_services \"\$a\"; settings put secure accessibility_enabled 1; sleep 1; settings put secure enabled_accessibility_services ''; settings put secure accessibility_enabled 0; sleep 1; settings put secure enabled_accessibility_services \"\$a\"; settings put secure accessibility_enabled 1 ;; esac"

if [ "${1:-}" = --clear ]; then
  tv "rm -rf $DIR/themes 2>/dev/null; content delete --uri $URI/themes 2>/dev/null; $RESTART; echo cleared" | tail -n 1
  exit 0
fi
THEMES_DIR="${1:-themes}"
[ -d "$THEMES_DIR" ] || { echo "no such themes directory: $THEMES_DIR" >&2; exit 1; }
tv "mkdir -p $DIR/themes" >/dev/null 2>&1 || true
for FOLDER in "$THEMES_DIR"/*/; do
  NAME=$(basename "$FOLDER")
  [ -f "$FOLDER/config.json" ] || continue
  "$PY" -I -c 'import json,sys; json.load(open(sys.argv[1], encoding="utf-8-sig"))' "$FOLDER/config.json" 2>/dev/null \
    || { echo "skip $NAME: config.json is not valid JSON" >&2; continue; }
  tv "mkdir -p $DIR/themes/$NAME" >/dev/null 2>&1 || true
  for FILE in config.json wallpaper.jpg screenshot.jpg; do
    [ -f "$FOLDER/$FILE" ] || continue
    "$PY" -I b64push.py "$FOLDER/$FILE" "$(stage "themes/$NAME/$FILE")" >/dev/null
    tv "$(put "themes/$NAME/$FILE")" >/dev/null
  done
  if [ -d "$FOLDER/wallpapers" ]; then
    for IMAGE in "$FOLDER"/wallpapers/*; do
      [ -f "$IMAGE" ] || continue
      case "$IMAGE" in *.[jJ][pP][gG]|*.[jJ][pP][eE][gG]|*.[pP][nN][gG]|*.[wW][eE][bB][pP])
        I=$(basename "$IMAGE")
        "$PY" -I b64push.py "$IMAGE" "$(stage "themes/$NAME/wallpapers/$I")" >/dev/null
        tv "$(put "themes/$NAME/wallpapers/$I")" >/dev/null ;;
      esac
    done
  fi
  echo "pushed $NAME"
done
tv "$RESTART; echo done" | tail -n 1
echo "Catalog on the TV. Undo with: tools/theme-catalog.sh --clear"
```

Make it executable: `chmod +x tools/theme-catalog.sh`.

- [ ] **Step 2: Add the Tools table row**

In `README.md`, add a row to the Tools table (after the `theme.sh` row):

```markdown
| [`tools/theme-catalog.sh`](tools/theme-catalog.sh) | Push every theme in [`themes/`](themes/) to the launcher's on-TV catalog, so the Themes row in Launcher settings can switch between them. `--clear` empties it. |
```

- [ ] **Step 3: Run the tools-table and theme tests**

Run: `python3 -m unittest tests.test_tools_table tests.test_themes -v`

Expected: PASS (the new script is listed; `themes/` is unchanged).

- [ ] **Step 4: Commit**

```bash
git add tools/theme-catalog.sh README.md
git commit -m "tools: theme-catalog.sh pushes all themes for the on-TV picker"
```

---

## Task 6: Real-TV verification and docs

- [ ] **Step 1: Build the signed launcher and install**

```bash
cd launcher && ./gradlew --no-daemon assembleRelease -q --console=plain
adb install -r app/build/outputs/apk/release/app-release.apk
```

(`install -r` is fine here: same signature, no uninstall.)

- [ ] **Step 2: Push the catalog**

```bash
env -u PYTHONHOME -u PYTHONPATH -u LD_LIBRARY_PATH -u APPIMAGE TV_HOST=10.168.100.10:5555 tools/theme-catalog.sh themes/
```

Expected: each theme printed as `pushed <name>` and `done`.

- [ ] **Step 3: Verify on the TV**

On the TV (or via the settings activity), open Launcher settings → **Themes** → confirm the list shows the themes with thumbnails → pick `midnight` → confirm Home shows the midnight wallpaper → open Themes again → pick **Built-in** → confirm the default look returns.

Take screenshots with `adb -s 10.168.100.10:5555 shell screencap -p ...` after each pick and confirm via a vision check. Blur any Wi-Fi name before a screenshot is committed anywhere.

- [ ] **Step 4: Confirm the input helper survived the restart**

After the catalog push's Home restart, confirm InputKeyService is still bound:

```bash
adb -s 10.168.100.10:5555 shell "dumpsys accessibility 2>/dev/null | grep -i InputKeyService"
```

If it is missing, re-enable it as in Phase 2 (`settings put secure enabled_accessibility_services com.gathin.tvhome/.InputKeyService` then `settings put secure accessibility_enabled 1`), and report it.

- [ ] **Step 5: Update the spec status and push**

Mark the spec `Status:` line "approved and implemented", then commit and push `main`.

---

## Final verification

- [ ] Full Python suite (as CI): `python3 -m unittest discover -s tests -v` (after `tools/venv` exists).
- [ ] Launcher: `cd launcher && ./gradlew --no-daemon testReleaseUnitTest lintRelease assembleRelease` — lint zero errors, APK under 589,824 B.
- [ ] All four CI jobs green (`lists-and-tools`, `tables`, `launcher`, `security-scan`).
- [ ] No stray files; `backup/`, `shots/`, `tools/adbkey/`, `tools/venv/` are gitignored and not committed.
