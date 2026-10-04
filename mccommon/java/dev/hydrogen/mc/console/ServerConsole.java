package dev.hydrogen.mc.console;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.console.ConsoleClient;
import dev.hydrogen.core.console.TickClock;
import dev.hydrogen.mc.loader.LoaderBridge;
import net.minecraft.commands.CommandSourceStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The console on a dedicated server. There is no chat and no keyboard there,
 * so codes and notices go to the server log, and the operator asks for a code
 * by typing {@code hydrogen console} into the server console.
 */
public final class ServerConsole {
	private static final Queue<Runnable> TASKS = new ConcurrentLinkedQueue<>();

	private ServerConsole() {
	}

	/** What both sides report about the install. Safe on client and server. */
	public static Map<String, Object> loaderInfo() {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("loader", LoaderBridge.name());
		m.put("loaderVersion", LoaderBridge.loaderVersion());
		m.put("mc", LoaderBridge.minecraftVersion());
		m.put("mods", LoaderBridge.mods());
		return m;
	}

	/** Called as the server thread starts. Does nothing in singleplayer, where the client owns the console. */
	public static void start() {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.dedicatedServer()) {
			return;
		}

		ConsoleClient.install(h, LoaderBridge.configDir(), new ConsoleClient.Host() {
			@Override
			public ConsoleClient.GameState state() {
				return new ConsoleClient.GameState(true, false, TickClock.players(), Double.NaN);
			}

			@Override
			public Map<String, Object> info() {
				return loaderInfo();
			}

			@Override
			public void tell(String message) {
				HLog.LOG.info("[console] {}", message);
			}

			@Override
			public void onGameThread(Runnable task) {
				TASKS.add(task);
			}

			@Override
			public String playerName() {
				return null;
			}
		});
	}

	/** End of every server tick: run what the console handed over. */
	public static void afterTick() {
		Runnable task;
		int n = 0;

		while (n++ < 16 && (task = TASKS.poll()) != null) {
			try {
				task.run();
			} catch (RuntimeException e) {
				HLog.warnOnce("console-server-task", "Hydrogen console: a change from the browser failed", e);
			}
		}
	}

	/**
	 * {@code hydrogen console}, {@code hydrogen console on} and {@code hydrogen console off}.
	 * Only for sources without an entity: the server console, or command blocks
	 * and functions an operator set up. A player can neither run nor see it.
	 */
	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.dedicatedServer()) {
			return;
		}

		dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("hydrogen")
				.requires(source -> source.getEntity() == null)
				.then(LiteralArgumentBuilder.<CommandSourceStack>literal("console")
						.executes(c -> run(""))
						.then(LiteralArgumentBuilder.<CommandSourceStack>literal("on").executes(c -> run("on")))
						.then(LiteralArgumentBuilder.<CommandSourceStack>literal("off").executes(c -> run("off")))));
	}

	private static int run(String arg) {
		ConsoleClient client = ConsoleClient.get();

		if (client == null) {
			HLog.LOG.info("Hydrogen console: still starting, try again in a moment");
			return 0;
		}

		client.serverCommand(arg);
		return 1;
	}
}
