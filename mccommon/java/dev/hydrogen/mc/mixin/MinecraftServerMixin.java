package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.console.TickClock;
import dev.hydrogen.core.cpu.ThreadRole;
import dev.hydrogen.mc.console.ServerConsole;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Places the server thread. In singleplayer it gets the fast cores the render
 * thread does not own; on a dedicated server it owns the best cores itself.
 *
 * Also times every tick for the console, and starts the console on a
 * dedicated server. runServer()V and tickServer(BooleanSupplier)V are the same
 * on every supported version.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "runServer", at = @At("HEAD"))
	private void hydrogen$bindServerThread(CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null && h.enabled()) {
			h.bindCurrentThread(ThreadRole.SERVER);
		}

		TickClock.reset();
		ServerConsole.start();
	}

	@Inject(method = "tickServer", at = @At("HEAD"))
	private void hydrogen$tickBegin(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
		TickClock.begin();
	}

	@Inject(method = "tickServer", at = @At("RETURN"))
	private void hydrogen$tickEnd(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
		TickClock.end(((MinecraftServer) (Object) this).getPlayerCount());
		ServerConsole.afterTick();
	}
}
