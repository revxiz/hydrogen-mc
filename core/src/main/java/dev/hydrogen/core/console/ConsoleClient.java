package dev.hydrogen.core.console;

import dev.hydrogen.core.BuildInfo;
import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.frame.FrameStats;
import dev.hydrogen.core.gpu.VramSnapshot;
import dev.hydrogen.core.hw.DisplayInfo;
import dev.hydrogen.core.hw.GpuInfo;
import dev.hydrogen.core.tune.Baseline;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * The game's side of the remote console. Off until the player turns it on.
 *
 * <p>Once on, the PC registers once and keeps a key in
 * {@code config/hydrogen-console.key}. A pairing code is made here, shown in
 * chat, and only its hash is sent. The live link is one WebSocket: the server
 * says how often to report (every 2 seconds while a browser watches, every 30
 * otherwise), and pushes switches and actions the moment a browser asks.
 *
 * <p>Everything runs on one daemon thread. Commands are checked against
 * {@link ConsoleSchema} and then handed to the game thread, because the
 * controllers they touch belong to it.
 */
public final class ConsoleClient {
	/** What the game side provides. Every method may be called from the console thread. */
	public interface Host {
		/** Live facts, read without locks. */
		GameState state();

		/** Loader, Minecraft version and the mod list. Called once per connection and after {@link #infoChanged}. */
		Map<String, Object> info();

		/** A line for the player's chat, or the server log on a dedicated server. Safe from any thread. */
		void tell(String message);

		/** Runs a task on the game thread at its next tick. */
		void onGameThread(Runnable task);

		/** The player's name, so log lines can be scrubbed of it. Null on a server. */
		String playerName();
	}

	/**
	 * @param players -1 when unknown
	 * @param fps     NaN when unknown, as on a dedicated server
	 */
	public record GameState(boolean inWorld, boolean paused, int players, double fps) {
		public static final GameState UNKNOWN = new GameState(false, false, -1, Double.NaN);
	}

	private static final String KEY_FILE = "hydrogen-console.key";
	private static final Pattern TOKEN = Pattern.compile("hd1\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}");
	private static final long SAMPLE_MS = 5_000L;
	private static final int HISTORY = 720;
	private static final int HISTORY_PER_SYNC = 240;
	private static final int LOG_PER_SYNC = 50;
	private static final int MAX_MESSAGE = 120_000;
	private static final long CONFIRM_MS = 10_000L;

	private static volatile ConsoleClient instance;

	private final Hydrogen h;
	private final HConfig config;
	private final Host host;
	private final Path keyFile;
	private final ScheduledExecutorService exec;
	private final HttpClient http;
	private final SecureRandom random = new SecureRandom();
	private final long startedMs = System.currentTimeMillis();

	// Everything below is only touched on the console thread.
	private final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();
	private final LinkedHashSet<String> seenCommands = new LinkedHashSet<>();
	private final List<String> acks = new ArrayList<>();
	private String base;
	private String token;
	private boolean running;
	private WebSocket socket;
	private long epoch;
	private int failures;
	private long have;
	private long logSeq = -1L;
	private int interval = 30;
	private boolean infoSent;
	private int browsers = -1;
	private long confirmUntil;
	private Scrubber scrubber;
	private ScheduledFuture<?> sampler;
	private ScheduledFuture<?> syncTask;
	private ScheduledFuture<?> pingTask;
	private ScheduledFuture<?> reconnectTask;
	private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);
	private volatile boolean infoDirty = true;

	private ConsoleClient(Hydrogen h, Path configDir, Host host) {
		this.h = h;
		this.config = h.config();
		this.host = host;
		this.keyFile = configDir.resolve(KEY_FILE);
		this.exec = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Hydrogen console");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(15))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build();
	}

	/** Called once the game side can answer {@link Host}. Starts at once when the console was left on. */
	public static synchronized ConsoleClient install(Hydrogen h, Path configDir, Host host) {
		if (instance == null) {
			instance = new ConsoleClient(h, configDir, host);
			instance.exec.execute(instance::startIfEnabled);
		}

		return instance;
	}

	public static ConsoleClient get() {
		return instance;
	}

	// ------------------------------------------------------------ entry points

	/** Alt+H in game: explains and asks for a second press the first time, then makes codes. */
	public void hotkey() {
		exec.execute(() -> {
			if (resolveBase() == null) {
				tellNoAddress();
				return;
			}

			if (config.bool("console.enabled")) {
				start();
				issueCode();
				return;
			}

			long now = System.currentTimeMillis();

			if (now < confirmUntil) {
				confirmUntil = 0L;
				enable();
				return;
			}

			confirmUntil = now + CONFIRM_MS;
			host.tell("The console lets you watch this game from a browser. While it is on, it sends frame times, "
					+ "memory, your PC's specs, the mod list and logged warnings (personal details removed) to "
					+ hostName() + ". Press Alt+H again within 10 seconds to turn it on.");
		});
	}

	/** The server console's {@code hydrogen console [on|off]}. Typing "on" is consent enough. */
	public void serverCommand(String arg) {
		exec.execute(() -> {
			if (resolveBase() == null) {
				tellNoAddress();
				return;
			}

			String a = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);

			if (a.equals("off")) {
				turnOff("The console is off, and this server's data was deleted from it.");
			} else if (config.bool("console.enabled")) {
				start();
				issueCode();
			} else if (a.equals("on")) {
				enable();
			} else {
				host.tell("The console is off. Run \"hydrogen console on\" to turn it on: it sends tick times, "
						+ "memory, the server's specs, the mod list and logged warnings (personal details removed) to "
						+ hostName() + " and prints a code for the browser.");
			}
		});
	}

	/** The GPU and screen became known, or the mod list changed. */
	public void infoChanged() {
		infoDirty = true;
	}

	// ------------------------------------------------------------- lifecycle

	private void startIfEnabled() {
		if (!config.bool("console.enabled")) {
			return;
		}

		if (resolveBase() == null) {
			HLog.once("console-no-url", "Hydrogen console is on, but this build has no console address; set console.url");
			return;
		}

		start();
	}

	private void enable() {
		config.set("console.enabled", "true");
		config.save();
		start();
		issueCode();
	}

	private void start() {
		if (running) {
			return;
		}

		running = true;
		failures = 0;
		scrubber = Scrubber.forThisPc(host.playerName());
		sampler = exec.scheduleAtFixedRate(this::sample, 0L, SAMPLE_MS, TimeUnit.MILLISECONDS);
		connect();
	}

	private void turnOff(String message) {
		running = false;
		cancel(sampler);
		cancel(syncTask);
		cancel(pingTask);
		cancel(reconnectTask);
		epoch++;

		if (socket != null) {
			socket.abort();
			socket = null;
		}

		if (loadToken() != null) {
			try {
				HttpResponse<String> r = call("DELETE", "/api/device", null);
				HLog.LOG.info("Hydrogen console: deleted this PC's data from the server ({})", r.statusCode());
			} catch (IOException | InterruptedException e) {
				HLog.LOG.warn("Hydrogen console: could not reach the server to delete this PC's data ({})", e.getMessage());
			}
		}

		try {
			Files.deleteIfExists(keyFile);
		} catch (IOException e) {
			HLog.LOG.warn("Hydrogen console: could not delete {} ({})", keyFile.getFileName(), e.getMessage());
		}

		token = null;
		history.clear();
		have = 0L;
		logSeq = -1L;
		browsers = -1;
		config.set("console.enabled", "false");
		config.save();
		host.tell(message);
	}

	// ---------------------------------------------------------------- server

	/**
	 * The address from the config, or the one this build carries. Only https, or
	 * plain http to this same machine for development.
	 */
	private String resolveBase() {
		String raw = config.raw("console.url");
		String url = HConfig.AUTO.equalsIgnoreCase(raw) || raw.isEmpty() ? BuildInfo.consoleUrl() : raw;

		if (url.isEmpty()) {
			return base = null;
		}

		try {
			URI u = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
			String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
			String hostName = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
			boolean local = hostName.equals("localhost") || hostName.equals("127.0.0.1") || hostName.equals("[::1]");

			if (u.getUserInfo() != null || u.getQuery() != null || !(scheme.equals("https") || (scheme.equals("http") && local))) {
				HLog.once("console-bad-url", "Hydrogen console: console.url must be an https address; ignoring " + url);
				return base = null;
			}

			return base = u.toString();
		} catch (IllegalArgumentException e) {
			HLog.once("console-bad-url", "Hydrogen console: console.url is not a valid address; ignoring it");
			return base = null;
		}
	}

	private String hostName() {
		try {
			return URI.create(base).getHost();
		} catch (RuntimeException e) {
			return base;
		}
	}

	private String newCodeHint() {
		return h.dedicatedServer() ? "Run \"hydrogen console\" for a new code." : "Press Alt+H for a new code.";
	}

	private String onAgainHint() {
		return h.dedicatedServer() ? "Run \"hydrogen console on\" to turn it on again." : "Press Alt+H to turn it on again.";
	}

	private void tellNoAddress() {
		host.tell("This copy of Hydrogen has no console address. Add console.url=https://... to config/hydrogen.properties.");
	}

	private HttpResponse<String> call(String method, String path, String body) throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", "Hydrogen/" + BuildInfo.version())
				.header("Accept", "application/json");

		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}

		if (body != null) {
			b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
		} else {
			b.method(method, HttpRequest.BodyPublishers.noBody());
		}

		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private String loadToken() {
		if (token != null) {
			return token;
		}

		try {
			if (Files.isRegularFile(keyFile)) {
				String t = Files.readString(keyFile, StandardCharsets.UTF_8).trim();

				if (TOKEN.matcher(t).matches()) {
					return token = t;
				}

				HLog.LOG.warn("Hydrogen console: {} is damaged; this PC will register again", keyFile.getFileName());
			}
		} catch (IOException e) {
			HLog.LOG.warn("Hydrogen console: could not read {} ({})", keyFile.getFileName(), e.getMessage());
		}

		return null;
	}

	/** First contact: the server makes this PC's id and secret and shows the secret exactly once. */
	private boolean register() throws IOException, InterruptedException {
		token = null;
		HttpResponse<String> r = call("POST", "/api/device/register", null);

		if (r.statusCode() == 429) {
			host.tell("The console has seen too many new PCs from this network in the last hour. Try again later.");
			return false;
		}

		Map<String, Object> body = Json.object(r.body());
		String t = body == null ? null : Json.str(body, "token");

		if (r.statusCode() != 201 || t == null || !TOKEN.matcher(t).matches()) {
			HLog.LOG.warn("Hydrogen console: registering failed ({})", r.statusCode());
			return false;
		}

		Path tmp = keyFile.resolveSibling(KEY_FILE + ".tmp");
		Files.writeString(tmp, t + "\n", StandardCharsets.UTF_8);

		try {
			Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Windows: the file sits in the user's own profile already.
		}

		Files.move(tmp, keyFile, StandardCopyOption.REPLACE_EXISTING);
		token = t;
		return true;
	}

	private boolean ensureToken() throws IOException, InterruptedException {
		return loadToken() != null || register();
	}

	private void forgetKey() {
		token = null;

		try {
			Files.deleteIfExists(keyFile);
		} catch (IOException ignored) {
			// Overwritten on the next registration anyway.
		}
	}

	/** A fresh code, registered by its hash only, shown to the player. */
	private void issueCode() {
		if (resolveBase() == null) {
			tellNoAddress();
			return;
		}

		try {
			if (!ensureToken()) {
				host.tell("Couldn't reach the console at " + hostName() + ". Try again in a minute.");
				return;
			}

			String code = PairCode.generate(random);
			String body = Json.write(Map.of("codeHash", PairCode.hash(code)));
			HttpResponse<String> r = call("POST", "/api/device/code", body);

			if (r.statusCode() == 401) {
				// The server forgot this PC (30 days unused); start over once.
				forgetKey();

				if (register()) {
					r = call("POST", "/api/device/code", body);
				}
			}

			if (r.statusCode() != 200) {
				host.tell("The console didn't accept a new code (" + r.statusCode() + "). Try again in a minute.");
				return;
			}

			host.tell("Console code: " + PairCode.format(code) + ". Enter it at " + base + "/console/ within 10 minutes. It works once.");

			if (socket == null && running) {
				cancel(reconnectTask);
				connect();
			}
		} catch (IOException | InterruptedException e) {
			host.tell("Couldn't reach the console at " + hostName() + ". Check your connection and try again.");
		}
	}

	// ----------------------------------------------------------------- the link

	private void connect() {
		if (!running || socket != null) {
			return;
		}

		try {
			if (!ensureToken()) {
				retryLater();
				return;
			}
		} catch (IOException | InterruptedException e) {
			retryLater();
			return;
		}

		long mine = ++epoch;
		String wsBase = base.startsWith("https:") ? "wss:" + base.substring(6) : "ws:" + base.substring(5);

		http.newWebSocketBuilder()
				.header("Authorization", "Bearer " + token)
				.connectTimeout(Duration.ofSeconds(15))
				.buildAsync(URI.create(wsBase + "/api/device/live"), new Listener(mine))
				.whenComplete((ws, err) -> exec.execute(() -> opened(mine, ws, err)));
	}

	private void opened(long mine, WebSocket ws, Throwable err) {
		if (mine != epoch || !running) {
			if (ws != null) {
				ws.abort();
			}

			return;
		}

		if (err != null) {
			Throwable cause = err.getCause() != null ? err.getCause() : err;

			if (cause instanceof WebSocketHandshakeException hs && hs.getResponse().statusCode() == 401) {
				HLog.LOG.info("Hydrogen console: the server no longer knows this PC; registering again");
				forgetKey();
				failures = 0;
				reconnectTask = exec.schedule(this::connect, 1L, TimeUnit.SECONDS);
				return;
			}

			retryLater();
			return;
		}

		socket = ws;
		infoSent = false;
		pingTask = exec.scheduleAtFixedRate(() -> send("ping"), 25L, 25L, TimeUnit.SECONDS);
	}

	private void closed(long mine, int code, String reason) {
		if (mine != epoch) {
			return;
		}

		socket = null;
		cancel(syncTask);
		cancel(pingTask);

		if (!running) {
			return;
		}

		if (code == 4001) {
			// The server deleted this PC: unused for 30 days, or turned off from another copy of this config.
			forgetKey();
			browsers = -1;
			host.tell("The console forgot this PC, so any linked browsers are unlinked. " + newCodeHint());
			failures = 0;
			reconnectTask = exec.schedule(this::connect, 2L, TimeUnit.SECONDS);
			return;
		}

		if (code == 4000) {
			// Another game with the same config folder took over the link; don't fight it.
			HLog.once("console-replaced", "Hydrogen console: another game using this config folder took over the link");
			failures = Math.max(failures, 5);
		}

		retryLater();
	}

	private void retryLater() {
		if (!running) {
			return;
		}

		long delay = Math.min(300L, 2L << Math.min(failures, 7));
		failures++;
		delay = Math.max(1L, Math.round(delay * (0.8D + 0.4D * ThreadLocalRandom.current().nextDouble())));

		if (config.bool("log.verbose")) {
			HLog.LOG.info("Hydrogen console: link down, retrying in {} s", delay);
		}

		cancel(reconnectTask);
		reconnectTask = exec.schedule(this::connect, delay, TimeUnit.SECONDS);
	}

	private void send(String text) {
		WebSocket ws = socket;

		if (ws == null) {
			return;
		}

		// One send at a time: the next waits for the previous, whether or not it worked.
		sendChain = sendChain.handle((x, e) -> null).thenCompose(v -> ws.sendText(text, true));
	}

	private void received(long mine, String text) {
		if (mine != epoch || !running || "pong".equals(text)) {
			return;
		}

		Map<String, Object> m = Json.object(text);

		if (m == null) {
			return;
		}

		String type = Json.str(m, "type");

		if ("hello".equals(type) || "ack".equals(type)) {
			have = Json.num(m, "have", have);
			logSeq = Json.num(m, "logSeq", logSeq);
			interval = (int) Math.max(1L, Math.min(300L, Json.num(m, "interval", interval)));
			receiveCommands(Json.list(m, "commands"));

			if ("hello".equals(type)) {
				failures = 0;
				browsers = (int) Json.num(m, "browsers", 0L);
				HLog.LOG.info("Hydrogen console: linked to {} ({} browser{} paired)", hostName(), browsers, browsers == 1 ? "" : "s");
				scheduleSync(200L);
			} else {
				scheduleSync(interval * 1000L);
			}
		} else if ("watch".equals(type)) {
			interval = (int) Math.max(1L, Math.min(300L, Json.num(m, "interval", interval)));
			scheduleSync(200L);
		} else if ("commands".equals(type)) {
			receiveCommands(Json.list(m, "commands"));
		} else if ("linked".equals(type)) {
			int now = (int) Json.num(m, "browsers", browsers);

			if (browsers >= 0 && now > browsers) {
				host.tell("A browser just linked to the console. " + now + (now == 1 ? " browser" : " browsers")
						+ " can see this game. Unlink them at " + base + "/console/.");
			}

			browsers = now;
		}
	}

	private void receiveCommands(List<Object> raw) {
		for (Object o : raw) {
			ConsoleSchema.Command c = ConsoleSchema.parse(o);

			if (c == null) {
				// Not something this game does. Acknowledge it anyway so it leaves the queue.
				if (o instanceof Map<?, ?> m && m.get("id") instanceof String id && id.matches("[A-Za-z0-9_-]{1,32}")
						&& remember(id)) {
					acks.add(id);
				}

				continue;
			}

			if (!remember(c.id())) {
				continue;
			}

			if ("consoleOff".equals(c.action())) {
				turnOff("The console was turned off from a browser, and this PC's data was deleted from it. " + onAgainHint());
				return;
			}

			host.onGameThread(() -> {
				apply(c);
				exec.execute(() -> {
					acks.add(c.id());
					scheduleSync(300L);
				});
			});
		}
	}

	private boolean remember(String id) {
		if (!seenCommands.add(id)) {
			return false;
		}

		if (seenCommands.size() > 200) {
			Iterator<String> it = seenCommands.iterator();
			it.next();
			it.remove();
		}

		return true;
	}

	/** Game thread. Switches only ever change for this session. */
	private void apply(ConsoleSchema.Command c) {
		try {
			if (c.toggle()) {
				config.override(c.key(), String.valueOf(c.value()));
				h.budget().refresh();
				HLog.LOG.info("Hydrogen console: {} set to {} for this session", c.key(), c.value());
				return;
			}

			switch (c.action()) {
				case "gc" -> h.gc().collectNow();
				case "recalibrate" -> h.calibrator().request(System.currentTimeMillis());
				case "resetScale" -> h.resolution().reset();
				default -> {
					// consoleOff is handled on the console thread before it gets here.
				}
			}

			HLog.LOG.info("Hydrogen console: ran {}", c.action());
		} catch (RuntimeException e) {
			HLog.warnOnce("console-apply-" + c.id(), "Hydrogen console: a change from the browser failed", e);
		}
	}

	// ------------------------------------------------------------------ data

	private void scheduleSync(long delayMs) {
		cancel(syncTask);
		syncTask = exec.schedule(this::sync, delayMs, TimeUnit.MILLISECONDS);
	}

	private void sync() {
		if (socket == null || !running) {
			return;
		}

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("type", "sync");

		if (!infoSent || infoDirty) {
			body.put("info", info());
			infoSent = true;
			infoDirty = false;
		}

		body.put("now", now());
		body.put("features", features());

		List<Map<String, Object>> fresh = new ArrayList<>();

		for (Map<String, Object> s : history) {
			if (((Number) s.get("t")).longValue() > have) {
				fresh.add(s);
			}
		}

		int chunk = body.containsKey("info") ? HISTORY_PER_SYNC / 2 : HISTORY_PER_SYNC;
		body.put("history", fresh.size() > chunk ? fresh.subList(0, chunk) : fresh);
		body.put("log", logLines());
		body.put("acks", new ArrayList<>(acks));
		acks.clear();

		String text = Json.write(body);

		if (text.length() > MAX_MESSAGE) {
			body.put("history", fresh.subList(0, Math.min(fresh.size(), 20)));
			text = Json.write(body);
		}

		send(text);

		// If the reply never comes, report again anyway.
		cancel(syncTask);
		syncTask = exec.schedule(this::sync, interval * 1000L + 15_000L, TimeUnit.MILLISECONDS);
	}

	private List<Map<String, Object>> logLines() {
		boolean all = config.bool("console.gameLog");
		List<Map<String, Object>> out = new ArrayList<>();

		for (LogFeed.Line l : LogFeed.INSTANCE.after(logSeq, LogFeed.CAPACITY)) {
			if (!all && !l.own()) {
				continue;
			}

			Map<String, Object> m = new LinkedHashMap<>();
			m.put("s", l.seq());
			m.put("t", l.time());
			m.put("level", l.error() ? "ERROR" : "WARN");
			m.put("src", scrubber.clean(l.source()));
			m.put("msg", scrubber.clean(l.message()));
			out.add(m);
		}

		return out.size() > LOG_PER_SYNC ? out.subList(out.size() - LOG_PER_SYNC, out.size()) : out;
	}

	private Map<String, Object> features() {
		Map<String, Object> m = new LinkedHashMap<>();

		for (String key : ConsoleSchema.FEATURES) {
			m.put(key, config.bool(key));
		}

		return m;
	}

	private static Double finite(double v) {
		return Double.isFinite(v) ? v : null;
	}

	/** One five-second reading for the charts. */
	private void sample() {
		try {
			GameState g = host.state();
			boolean client = !h.dedicatedServer();
			FrameStats stats = h.stats();
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("t", System.currentTimeMillis());
			s.put("fps", client && g.inWorld() ? finite(g.fps()) : null);
			s.put("p95", client && g.inWorld() && stats.usable() ? stats.p95Ms() : null);
			s.put("heap", h.gc().heapUsedBytes() / 1048576L);
			s.put("scale", client && g.inWorld() ? h.resolution().scale() : null);
			s.put("boost", h.governor().boosted());
			s.put("mspt", finite(TickClock.meanMs()));
			history.addLast(s);

			while (history.size() > HISTORY) {
				history.removeFirst();
			}
		} catch (RuntimeException e) {
			HLog.warnOnce("console-sample", "Hydrogen console: reading the game's numbers failed", e);
		}
	}

	private Map<String, Object> now() {
		GameState g = host.state();
		boolean client = !h.dedicatedServer();
		FrameStats stats = h.stats();
		boolean frames = client && g.inWorld() && stats.usable();
		VramSnapshot vram = h.vram();
		Baseline b = h.budget().baseline();
		long heapMax = Runtime.getRuntime().maxMemory();

		Map<String, Object> m = new LinkedHashMap<>();
		m.put("t", System.currentTimeMillis());
		m.put("inWorld", h.dedicatedServer() || g.inWorld());
		m.put("paused", g.paused());
		m.put("players", g.players() >= 0 ? g.players() : TickClock.players() >= 0 ? TickClock.players() : null);
		m.put("fps", client ? finite(g.fps()) : null);
		m.put("p50", frames ? stats.p50Ms() : null);
		m.put("p95", frames ? stats.p95Ms() : null);
		m.put("p99", frames ? stats.p99Ms() : null);
		m.put("stall", frames ? stats.stallRatio() : null);
		m.put("target", client ? h.budget().targetFrameMs() : null);
		m.put("scale", client ? h.resolution().scale() : null);
		m.put("scaleReason", client ? h.resolution().reason() : null);
		m.put("boost", h.governor().boosted());
		m.put("govMode", h.governor().mode());
		m.put("switches", h.governor().switchCount());
		m.put("heapUsedMb", h.gc().heapUsedBytes() / 1048576L);
		m.put("heapMaxMb", heapMax == Long.MAX_VALUE ? null : heapMax / 1048576L);
		m.put("gc", h.gc().collector());
		m.put("gcRequests", h.gc().stats().scheduledSweeps());
		m.put("vramUsedMb", vram.known() ? vram.usedKb() / 1024L : null);
		m.put("mspt", finite(TickClock.meanMs()));
		m.put("threadsMoved", h.binder().nativeBoundThreads());
		m.put("plan", h.binder().note());

		if (b.valid()) {
			Map<String, Object> bm = new LinkedHashMap<>();
			bm.put("p50", b.p50Ms());
			bm.put("p95", b.p95Ms());
			bm.put("headroom", b.headroom(h.budget().targetFrameMs()));
			m.put("baseline", bm);
		}

		m.put("uptimeS", uptimeSeconds());
		return m;
	}

	private long uptimeSeconds() {
		try {
			return ManagementFactory.getRuntimeMXBean().getUptime() / 1000L;
		} catch (Throwable t) {
			return (System.currentTimeMillis() - startedMs) / 1000L;
		}
	}

	private Map<String, Object> info() {
		Map<String, Object> m = new LinkedHashMap<>();

		try {
			m.putAll(host.info());
		} catch (RuntimeException e) {
			HLog.warnOnce("console-info", "Hydrogen console: reading the mod list failed", e);
		}

		m.put("mod", BuildInfo.version());
		m.put("side", h.dedicatedServer() ? "server" : "client");
		m.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
		m.put("java", System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");

		var cpu = h.hardware().cpu();
		String name = CpuName.read();
		String shape = cpu.physicalCount() + " cores, " + cpu.logicalCount() + " threads";
		m.put("cpu", name == null ? shape : name + ", " + shape);

		GpuInfo gpu = h.hardware().gpu();

		if (!h.dedicatedServer() && gpu != null && !GpuInfo.UNKNOWN.equals(gpu)) {
			m.put("gpu", gpu.renderer());
			m.put("vramTotalMb", gpu.vramKnown() ? gpu.vramTotalKb() / 1024L : null);
			DisplayInfo d = h.hardware().display();
			m.put("display", d.framebufferWidth() + "x" + d.framebufferHeight());
			m.put("refreshHz", d.refreshHz());
		}

		return m;
	}

	private static void cancel(ScheduledFuture<?> f) {
		if (f != null) {
			f.cancel(false);
		}
	}

	/** Feeds whole messages to the console thread; the socket hands text over in pieces. */
	private final class Listener implements WebSocket.Listener {
		private final long mine;
		private final StringBuilder partial = new StringBuilder();

		Listener(long mine) {
			this.mine = mine;
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			// Calls for one socket never overlap: the next piece is only requested below.
			if (partial.length() + data.length() > 1_048_576) {
				partial.setLength(0);
				ws.abort();
				return null;
			}

			partial.append(data);

			if (last) {
				String text = partial.toString();
				partial.setLength(0);
				exec.execute(() -> received(mine, text));
			}

			ws.request(1L);
			return null;
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			exec.execute(() -> closed(mine, code, reason));
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error) {
			exec.execute(() -> closed(mine, 1006, String.valueOf(error.getMessage())));
		}
	}
}
