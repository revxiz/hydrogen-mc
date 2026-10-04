package dev.hydrogen.core.console;

import java.util.regex.Pattern;

/**
 * Takes personal details out of log lines before they leave the PC: home
 * folders, IP addresses, email addresses, UUIDs, URL queries, things that look
 * like secrets, and the player's own name.
 *
 * It errs on the side of removing too much. A four-part version number can
 * come out as an address, which costs nothing compared with leaking one.
 */
public final class Scrubber {
	private static final Pattern WINDOWS_HOME =
			// Windows user folders may hold spaces ("Alex Smith"), so only a separator ends the name.
			Pattern.compile("(?i)([a-z]:[\\\\/]+(?:users|documents and settings)[\\\\/]+)[^\\\\/\\r\\n\"':;]+");
	private static final Pattern UNIX_HOME = Pattern.compile("(/(?:home|Users)/)[^/\\s\"':;]+");
	private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+");
	private static final Pattern URL_QUERY = Pattern.compile("(https?://[^\\s?#\"']+)[?#][^\\s\"']*");
	private static final Pattern SECRET = Pattern.compile(
			"(?i)\\b(access[_-]?token|token|session(?:id)?|password|passwd|secret|api[_-]?key|auth(?:orization)?)(\\s*[=:]\\s*)(\"[^\"]*\"|bearer\\s+\\S+|\\S+)");
	private static final Pattern UUID_PATTERN =
			Pattern.compile("(?i)\\b[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}\\b");
	private static final Pattern IPV4 = Pattern.compile(
			"(?<![\\w.])(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?![\\w.]*\\d)");
	// Four or more groups, or any form with "::", so clock times like 12:00:01 survive.
	private static final Pattern IPV6 = Pattern.compile(
			"(?i)(?<![\\w:])(?:(?:[0-9a-f]{1,4}:){4,7}[0-9a-f]{1,4}|(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::(?:[0-9a-f]{1,4}:)*[0-9a-f]{1,4})(?![\\w:])");

	private final Pattern homePattern;
	private final Pattern playerPattern;

	/**
	 * @param home   the user's home folder, replaced wherever it appears
	 * @param player the player's name, or null on a server
	 */
	public Scrubber(String home, String player) {
		this.homePattern = home != null && home.length() > 3
				? Pattern.compile(Pattern.quote(home), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
				: null;
		this.playerPattern = player != null && player.length() >= 3
				? Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(player) + "(?![A-Za-z0-9_])", Pattern.CASE_INSENSITIVE)
				: null;
	}

	public static Scrubber forThisPc(String player) {
		return new Scrubber(System.getProperty("user.home"), player);
	}

	public String clean(String text) {
		if (text == null || text.isEmpty()) {
			return text;
		}

		String s = text;

		if (homePattern != null) {
			s = homePattern.matcher(s).replaceAll("~");
		}

		s = WINDOWS_HOME.matcher(s).replaceAll("$1<user>");
		s = UNIX_HOME.matcher(s).replaceAll("$1<user>");
		s = URL_QUERY.matcher(s).replaceAll("$1?<hidden>");
		s = SECRET.matcher(s).replaceAll("$1$2<hidden>");
		s = EMAIL.matcher(s).replaceAll("<email>");
		s = UUID_PATTERN.matcher(s).replaceAll("<uuid>");
		s = IPV6.matcher(s).replaceAll("<ip>");
		s = IPV4.matcher(s).replaceAll("<ip>");

		if (playerPattern != null) {
			s = playerPattern.matcher(s).replaceAll("<player>");
		}

		return s;
	}
}
