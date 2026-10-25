# On-TV Theme Catalog — Design

Date: 2026-10-25
Status: approved (Approach 1: copy into overlay; include all themes)

## Goal

Let the owner browse and apply the repo's themes from the launcher's Settings on the TV itself,
instead of pushing one theme at a time from a computer.

## Non-goals

- No theme *editing* on the TV (keys stay in `themes/<name>/config.json`, edited in the repo).
- No network, no new dependencies, no APK-bundled themes.
- No change to how a single theme is pushed today (`tools/theme.sh` keeps working unchanged).

## Constraints (from `AGENTS.md`, all satisfied)

- Off by default: the Themes row appears only when the catalog is present.
- No `INTERNET` permission; no new dependencies (plain Java file I/O).
- Themes live in the launcher's *data*, not the APK, so the APK size is unaffected.

## Architecture

Three pieces: a push tool, a catalog scanner, and a picker that applies by copying into the
existing overlay.

### 1. Push tool — `tools/theme-catalog.sh`

- Pushes every folder in `themes/` (all of them) to the launcher's external files directory as
  `files/themes/<name>/`, each containing:
  - `config.json` (required),
  - `wallpaper.jpg` or a `wallpapers/` collection (plus `captions.txt` if present),
  - `screenshot.jpg` as the thumbnail.
- Reuses the existing `ThemeProvider` write path
  (`content write --uri content://com.gathin.tvhome.theme/themes/<name>/...`), the same mechanism
  `tools/theme.sh` uses, so there is no new transport code. Files are staged in `/data/local/tmp`
  and written through the provider (with the `cp` fallback), exactly as `theme.sh` does.
- `tools/theme-catalog.sh --clear` removes the whole `themes/` catalog.
- Mirrors `theme.sh`'s shell structure (staging + `put` helper). No PowerShell twin required for
  this fork.

### 2. Catalog scanner — new `ThemeCatalog.java`

- Lists `files/themes/*/` directories.
- For each, reads the folder name (theme name) and its `screenshot.jpg` (thumbnail); a folder
  without `config.json` is skipped; a missing `screenshot.jpg` gets a neutral placeholder.
- Exposes a small `ThemeEntry {name, thumbnail}` list, null/empty when the catalog is absent.

### 3. Themes row + picker — `LauncherSettingsActivity`

- Adds a **Themes** row to Launcher settings, shown only when `ThemeCatalog` returns a non-empty
  list (off by default).
- Tapping it opens a picker list of thumbnails + names, following the existing wallpaper-picker
  pattern already in `LauncherSettingsActivity`.
- Selecting a theme applies it; a "Built-in" entry clears the overlay back to `config.xml`.

### Apply: copy into the overlay (Approach 1)

On selection, the launcher (within its own external files dir):
1. Copies `files/themes/<name>/config.json` → `files/config.json`.
2. Clears `files/wallpapers/` and copies `files/themes/<name>/wallpapers/*` (or `wallpaper.jpg`)
   into place.
3. Writes a fresh `theme_revision` (so on-TV layout/motion choices reset, matching a `theme.sh`
   push).
4. Restarts Home so `Config` re-reads the overlay.

The existing `Config`/`WallpaperRotation` loading path is **unchanged**: it keeps reading
`files/config.json` and `files/wallpapers/` as it does today. The catalog is only a UI plus a
copy step.

## Data flow

```
tools/theme-catalog.sh  →  files/themes/<name>/{config.json, wallpapers/*, screenshot.jpg}
Settings → Themes  →  picker (thumbnails)
pick "midnight"  →  copy midnight/* into files/ overlay + bump theme_revision → Home reloads
pick "Built-in"  →  clear overlay → Home reloads
```

## Error handling

- A catalog folder without `config.json` is skipped (never listed).
- A copy failure keeps the previous theme and shows a toast; the selection is not half-applied
  (config.json is written last, after wallpapers).
- An empty or absent catalog hides the Themes row entirely.
- A missing thumbnail renders a neutral placeholder, never a blank tile.

## Testing

- JVM unit tests (repo `src/test`, no device): `ThemeCatalog` enumeration (skips config-less
  folders, handles missing thumbnails), and the copy step's file mapping (config + wallpapers +
  revision bump, order of writes).
- Real-TV check: `tools/theme-catalog.sh themes/`, then Settings → Themes → pick `midnight` →
  verify Home shows it; pick `Built-in` → verify the default returns.
- `tests/test_themes.py` and the existing `security-scan`/`lists-and-tools` CI jobs must stay green.

## Out of scope / later

- Theme editing, preview before apply, per-theme sub-settings, and network theme sources.
- Upstreaming (Phase 4): this is a fork feature; if upstreamed later it would need a broader
  discussion (new on-TV UI surface).
