package dev.hydrogen.mc.mixin;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.cpu.ThreadRole;
import dev.hydrogen.mc.ClientBridge;
import dev.hydrogen.mc.HydrogenClient;
import dev.hydrogen.mc.render.RenderScaler;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame boundary, client tick and render thread binding.
 *
 * {@code runTick} is the whole client frame including the swap, so wrapping it
 * gives the wall time the player actually waits. The end of {@code tick} drives
 * {@link HydrogenClient}, which is what lets Hydrogen run without any loader's
 * event API. All three methods are identical on every supported version.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {
	@Unique
	private long hydrogen$frameStart;

	@Inject(method = "run", at = @At("HEAD"))
	private void hydrogen$bindRenderThread(CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null) {
			h.bindCurrentThread(ThreadRole.RENDER);
		}
	}

	@Inject(method = "runTick(Z)V", at = @At("HEAD"))
	private void hydrogen$frameBegin(boolean renderLevel, CallbackInfo ci) {
		hydrogen$frameStart = System.nanoTime();
		HydrogenClient.frameClockMs = System.currentTimeMillis();
		// If anything threw out of a world render last frame, the real main target is put back here.
		RenderScaler.ensureRestored((Minecraft) (Object) this);
	}

	@Inject(method = "runTick(Z)V", at = @At("RETURN"))
	private void hydrogen$frameEnd(boolean renderLevel, CallbackInfo ci) {
		Hydrogen h = Hydrogen.get();

		if (h != null && hydrogen$frameStart != 0L) {
			Minecraft mc = (Minecraft) (Object) this;
			h.onFrameEnd(System.nanoTime() - hydrogen$frameStart, HydrogenClient.inWorld(mc), ClientBridge.screenOpen(mc));
		}
	}

	@Inject(method = "tick()V", at = @At("TAIL"))
	private void hydrogen$clientTick(CallbackInfo ci) {
		HydrogenClient.onEndTick((Minecraft) (Object) this);
	}
}
