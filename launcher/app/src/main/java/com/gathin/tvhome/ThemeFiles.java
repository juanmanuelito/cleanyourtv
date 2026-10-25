package com.gathin.tvhome;

import java.util.List;
import java.util.regex.Pattern;

/** The files ThemeProvider may write or delete under Config.dir, by the URI's path segments. */
final class ThemeFiles {
    static final String WALLPAPERS = "wallpapers";
    /** The names tools/theme.sh accepts for a collection image, and the types WallpaperRotation reads. */
    private static final Pattern IMAGE = Pattern.compile("[A-Za-z0-9_.-]+\\.(jpg|jpeg|png|webp)",
            Pattern.CASE_INSENSITIVE);

    /** A theme name in the catalog: no slashes, no dots-as-parents, so it can never leave the theme dir. */
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

    /** True for "wallpapers" alone: a delete of it clears the collection's images. */
    static boolean isCollection(List<String> segments) {
        return segments != null && segments.size() == 1 && WALLPAPERS.equals(segments.get(0));
    }

    static boolean collectionImage(String name) {
        return name != null && !name.startsWith(".") && IMAGE.matcher(name).matches();
    }
}
