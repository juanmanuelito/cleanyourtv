package com.gathin.tvhome;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
}
