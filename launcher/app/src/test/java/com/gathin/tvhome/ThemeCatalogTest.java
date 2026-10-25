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
}
