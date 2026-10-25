package com.gathin.tvhome;

import org.junit.Test;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public class ThemeCatalogTest {

    @Test public void listSkipsFoldersWithoutConfig() throws IOException {
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

    @Test public void applyReturnsFalseOnCopyFailure() {
        File dir = new File(System.getProperty("java.io.tmpdir"), "cyt-copyfail-" + System.nanoTime());
        File theme = new File(dir, "themes/broken");
        assertTrue(new File(theme, "wallpapers").mkdirs());
        write(new File(theme, "config.json"), "{}");
        write(new File(theme, "wallpapers/dusk.jpg"), "img");
        write(new File(dir, "wallpapers"), "not a directory");
        assertFalse(ThemeCatalog.apply(dir, "broken"));
        assertFalse(new File(dir, "config.json").exists());
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
}
