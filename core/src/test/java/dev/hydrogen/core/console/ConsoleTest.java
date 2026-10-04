package dev.hydrogen.core.console;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleTest {
	// ------------------------------------------------------------ pair codes

	@Test
	void codesUseThirtyTwoSymbolsAndTwelvePlaces() {
		assertEquals(32, new HashSet<>(List.of(PairCode.ALPHABET.split(""))).size());
		String code = PairCode.generate(new SecureRandom());
		assertEquals(12, code.length());
		assertTrue(code.chars().allMatch(c -> PairCode.ALPHABET.indexOf(c) >= 0));
	}

	/** The Worker's tests check this same vector, so both sides hash alike. */
	@Test
	void hashMatchesTheServer() {
		assertEquals("0fd08794a3d3355c066819f581cc5af9a04ce68a925f479bd8ea610c6dd938f4", PairCode.hash("H7QK2MXD9RTC"));
	}

	@Test
	void codesAreShownInThreeGroups() {
		assertEquals("H7QK-2MXD-9RTC", PairCode.format("H7QK2MXD9RTC"));
	}

	// ------------------------------------------------------------------ JSON

	@Test
	void jsonRoundTrips() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("type", "sync");
		m.put("n", 6.9449);
		m.put("whole", 12.0);
		m.put("count", 3);
		m.put("on", true);
		m.put("text", "line \"one\"\nline two\u0001");
		m.put("list", List.of(1, 2.5, "x"));
		m.put("skipped", null);

		String text = Json.write(m);
		assertEquals("{\"type\":\"sync\",\"n\":6.94,\"whole\":12,\"count\":3,\"on\":true,"
				+ "\"text\":\"line \\\"one\\\"\\nline two\\u0001\",\"list\":[1,2.5,\"x\"]}", text);

		Map<String, Object> back = Json.object(text);
		assertNotNull(back);
		assertEquals("line \"one\"\nline two\u0001", back.get("text"));
		assertEquals(6.94, (Double) back.get("n"), 1e-9);
		assertEquals(List.of(1.0, 2.5, "x"), back.get("list"));
	}

	@Test
	void jsonWritesNonFiniteNumbersAsNull() {
		assertEquals("[null,null]", Json.write(List.of(Double.NaN, Double.POSITIVE_INFINITY)));
	}

	@Test
	void jsonRefusesGarbage() {
		assertNull(Json.object("not json"));
		assertNull(Json.object("[1,2]"));
		assertNull(Json.object("{\"a\":1} trailing"));
		assertNull(Json.object("{\"a\":\"unterminated}"));
		assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100) + "]".repeat(100)));
	}

	// -------------------------------------------------------------- scrubbing

	private final Scrubber scrub = new Scrubber("/home/alex", "Steve_42");

	@Test
	void homeFoldersGo() {
		assertEquals("Loading ~/.minecraft/mods/x.jar", scrub.clean("Loading /home/alex/.minecraft/mods/x.jar"));
		assertEquals("C:\\Users\\<user>\\AppData\\Roaming", scrub.clean("C:\\Users\\Alex Smith\\AppData\\Roaming"));
		assertEquals("C:/Users/<user>/AppData", scrub.clean("C:/Users/alex/AppData"));
		assertEquals("/Users/<user>/Library", scrub.clean("/Users/someone/Library"));
		assertEquals("/home/<user>/x", scrub.clean("/home/bob/x"));
	}

	@Test
	void addressesGo() {
		assertEquals("Connecting to <ip>, 25565", scrub.clean("Connecting to 192.168.1.20, 25565"));
		assertEquals("peer <ip> left", scrub.clean("peer 2001:db8:85a3:0:0:8a2e:370:7334 left"));
		assertEquals("peer <ip> left", scrub.clean("peer fe80::1 left"));
		assertEquals("[12:00:01] tick took 300ms", scrub.clean("[12:00:01] tick took 300ms"));
		assertEquals("Minecraft 1.21.1 on Fabric 0.17.2", scrub.clean("Minecraft 1.21.1 on Fabric 0.17.2"));
	}

	@Test
	void contactsSecretsAndNamesGo() {
		assertEquals("mail <email> now", scrub.clean("mail alex.smith+mc@example.co.uk now"));
		assertEquals("GET https://api.example.com/v1?<hidden> failed", scrub.clean("GET https://api.example.com/v1?key=abc&x=1 failed"));
		assertEquals("accessToken=<hidden> expired", scrub.clean("accessToken=eyJhbGciOi.abc.def expired"));
		assertEquals("Authorization: <hidden>", scrub.clean("Authorization: Bearer abc123"));
		assertEquals("<player> joined, <player> left", scrub.clean("Steve_42 joined, steve_42 left"));
		assertEquals("Steve_420 is someone else", scrub.clean("Steve_420 is someone else"));
		assertEquals("entity <uuid> removed", scrub.clean("entity 069a79f4-44e9-4726-a5be-fca90e38aaf5 removed"));
	}

	// -------------------------------------------------------------- log feed

	@Test
	void linesAreNumberedAndPagedFromTheNewest() {
		LogFeed feed = new LogFeed();

		for (int i = 0; i < 5; i++) {
			feed.add(1_000L + i * 2_000L, i == 4, "src", "line " + i, false);
		}

		List<LogFeed.Line> all = feed.after(-1L, 50);
		assertEquals(5, all.size());
		assertEquals(0L, all.get(0).seq());
		assertTrue(all.get(4).error());
		assertEquals(List.of(3L, 4L), feed.after(2L, 50).stream().map(LogFeed.Line::seq).toList());
		assertEquals(List.of(4L), feed.after(-1L, 1).stream().map(LogFeed.Line::seq).toList());
	}

	@Test
	void aFloodIsCutAndCounted() {
		LogFeed feed = new LogFeed();

		for (int i = 0; i < 500; i++) {
			feed.add(10_000L, false, "spam", "again", false);
		}

		feed.add(11_500L, false, "x", "after", false);
		List<LogFeed.Line> lines = feed.after(-1L, 500);
		assertEquals(LogFeed.PER_SECOND + 2, lines.size());
		assertEquals("480 more lines in one second were left out", lines.get(LogFeed.PER_SECOND).message());
		assertEquals("after", lines.get(lines.size() - 1).message());
	}

	@Test
	void theRingKeepsTheNewest() {
		LogFeed feed = new LogFeed();

		for (int i = 0; i < LogFeed.CAPACITY + 30; i++) {
			feed.add(i * 1_000L, false, "s", "m" + i, false);
		}

		List<LogFeed.Line> lines = feed.after(-1L, 1_000);
		assertEquals(LogFeed.CAPACITY, lines.size());
		assertEquals("m30", lines.get(0).message());
	}

	@Test
	void sourcesAreShortened() {
		assertEquals("minecraft/SpriteContents", LogFeed.shortSource("net.minecraft.client.renderer.texture.SpriteContents"));
		assertEquals("minecraft", LogFeed.shortSource("net.minecraft.class_3176"));
		assertEquals("ModelManager", LogFeed.shortSource("com.example.mymod.ModelManager"));
		assertEquals("Sodium", LogFeed.shortSource("Sodium"));
		assertEquals("game", LogFeed.shortSource(""));
	}

	// --------------------------------------------------------------- commands

	@Test
	void onlyListedCommandsPass() {
		ConsoleSchema.Command toggle = ConsoleSchema.parse(Map.of("id", "a1", "type", "toggle", "key", "drs.enabled", "value", false));
		assertNotNull(toggle);
		assertTrue(toggle.toggle());
		assertFalse(toggle.value());

		assertNotNull(ConsoleSchema.parse(Map.of("id", "a2", "type", "action", "name", "gc")));

		assertNull(ConsoleSchema.parse(Map.of("id", "a3", "type", "toggle", "key", "cpu.affinity.renderCores", "value", true)));
		assertNull(ConsoleSchema.parse(Map.of("id", "a4", "type", "toggle", "key", "drs.enabled", "value", "false")));
		assertNull(ConsoleSchema.parse(Map.of("id", "a5", "type", "action", "name", "exec")));
		assertNull(ConsoleSchema.parse(Map.of("id", "../x", "type", "action", "name", "gc")));
		assertNull(ConsoleSchema.parse("gc"));
	}
}
