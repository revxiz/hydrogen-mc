package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.cpu.ThreadRole;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Places the server thread. In singleplayer it gets the fast cores the render
 * thread does not own; on a dedicated server it owns the best cores itself.
 * runServer()V is the same on every supported version.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
	@Inject(method = "runServer", at = @At("HEAD"))
	private void hydrogen$bindServerThread(CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null && h.enabled()) {
			h.bindCurrentThread(ThreadRole.SERVER);
		}
	}
}
