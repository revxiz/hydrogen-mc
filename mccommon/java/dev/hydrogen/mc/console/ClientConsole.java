package dev.hydrogen.mc.console;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hydrogen.core.HLog;
import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.console.ConsoleClient;
import dev.hydrogen.core.console.TickClock;
import dev.hydrogen.mc.ClientBridge;
import dev.hydrogen.mc.loader.LoaderBridge;
import net.minecraft.client.Minecraft;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * The console in the game client: the Alt+H key, chat lines, and the hand-off
 * of browser changes onto the game thread. Runs at the start of every client
 * tick, before Hydrogen checks its own master switch, so a browser can turn
 * Hydrogen back on after turning it off.
 */
public final class ClientConsole {
	private static final Queue<Runnable> TASKS = new ConcurrentLinkedQueue<>();
	private static final Queue<String> LINES = new ConcurrentLinkedQueue<>();

	private static volatile ConsoleClient.GameState state = ConsoleClient.GameState.UNKNOWN;
	private static volatile String player;
	private static boolean installed;
	private static boolean keyWasDown;

	private ClientConsole() {
	}

	public static void tick(Minecraft mc) {
		Hydrogen h = Hydrogen.get();

		if (h == null) {
			return;
		}

		if (!installed) {
			installed = true;
			player = mc.getUser().getName();
			ConsoleClient.install(h, LoaderBridge.configDir(), HOST);
		}

		boolean inWorld = mc.level != null && mc.player != null;
		state = new ConsoleClient.GameState(inWorld, mc.isPaused(), TickClock.players(), mc.getFps());

		pollHotkey(mc);
		runTasks();

		// Lines wait for a world, where the chat is on screen.
		if (inWorld) {
			String line;

			while ((line = LINES.poll()) != null) {
				ClientBridge.chat(mc, "[Hydrogen] " + line);
			}
		}
	}

	/** Alt+H, on the press only, and never while a screen such as chat has the keyboard. */
	private static void pollHotkey(Minecraft mc) {
		boolean down = !ClientBridge.screenOpen(mc)
				&& ClientBridge.keyDown(mc, InputConstants.KEY_H)
				&& (ClientBridge.keyDown(mc, InputConstants.KEY_LALT) || ClientBridge.keyDown(mc, InputConstants.KEY_RALT));

		if (down && !keyWasDown) {
			ConsoleClient client = ConsoleClient.get();

			if (client != null) {
				client.hotkey();
			}
		}

		keyWasDown = down;
	}

	private static void runTasks() {
		Runnable task;
		int n = 0;

		while (n++ < 16 && (task = TASKS.poll()) != null) {
			try {
				task.run();
			} catch (RuntimeException e) {
				HLog.warnOnce("console-client-task", "Hydrogen console: a change from the browser failed", e);
			}
		}
	}

	private static final ConsoleClient.Host HOST = new ConsoleClient.Host() {
		@Override
		public ConsoleClient.GameState state() {
			return state;
		}

		@Override
		public Map<String, Object> info() {
			return ServerConsole.loaderInfo();
		}

		@Override
		public void tell(String message) {
			// Bounded, in case the player sits in menus while notices pile up.
			if (LINES.size() < 20) {
				LINES.add(message);
			}
		}

		@Override
		public void onGameThread(Runnable task) {
			TASKS.add(task);
		}

		@Override
		public String playerName() {
			return player;
		}
	};
}
