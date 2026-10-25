package com.gathin.tvhome;

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
