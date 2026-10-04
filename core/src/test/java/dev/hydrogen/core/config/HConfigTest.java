package dev.hydrogen.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HConfigTest {
	@Test
	void inlineCommentsAreStripped() {
		assertEquals("0.70", HConfig.stripInlineComment("0.70                       # never scale below this"));
		assertEquals("true", HConfig.stripInlineComment("true\t# on"));
		assertEquals("auto", HConfig.stripInlineComment("auto"));
		// A hash with no whitespace in front is part of the value.
		assertEquals("a#b", HConfig.stripInlineComment("a#b"));
	}

	@Test
	void copiedReadmeLineParses(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("hydrogen.properties");
		Files.writeString(file, "drs.minScale=0.55   # floor\ngc.enabled=false  # off\n", StandardCharsets.UTF_8);

		HConfig c = new HConfig(file);

		assertEquals(0.55D, c.fixed("drs.minScale"), 1.0E-9);
		assertFalse(c.bool("gc.enabled"));
	}

	@Test
	void missingKeysAreAddedWithoutLosingUserValues(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("hydrogen.properties");
		Files.writeString(file, "drs.minScale=0.5\n", StandardCharsets.UTF_8);

		new HConfig(file);
		String written = Files.readString(file, StandardCharsets.UTF_8);

		assertTrue(written.contains("drs.minScale=0.5"));
		assertTrue(written.contains("gc.allowWhileIdle=auto"));
	}

	@Test
	void runtimeOverridesAreNeverSaved(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("hydrogen.properties");
		HConfig c = new HConfig(file);

		c.override("vram.enabled", "false");
		assertFalse(c.bool("vram.enabled"));

		c.save();
		assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("vram.enabled=true"));
		assertTrue(new HConfig(file).bool("vram.enabled"));
	}

	@Test
	void garbageFallsBackToDefaults(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("hydrogen.properties");
		Files.writeString(file, "drs.minScale=banana\naudio.pressureAt=NaN\n", StandardCharsets.UTF_8);

		HConfig c = new HConfig(file);

		assertEquals(0.70D, c.fixed("drs.minScale"), 1.0E-9);
		assertEquals(0.75D, c.fixed("audio.pressureAt"), 1.0E-9);
	}
}
