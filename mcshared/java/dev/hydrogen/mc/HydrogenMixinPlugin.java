package dev.hydrogen.mc;

import dev.hydrogen.core.HLog;
import dev.hydrogen.mc.compat.ModProbe;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Decides at class-load time which hooks are safe on this install.
 *
 * Framebuffer scaling is skipped when a Vulkan backend owns the pipeline, and
 * chunk ordering is skipped when Sodium supplies its own scheduler. Hydrogen
 * then only reads metrics around those mods, which is what keeps it conflict
 * free out of the box.
 */
public final class HydrogenMixinPlugin implements IMixinConfigPlugin {
	private static final Set<String> DRS_MIXINS = Set.of(
			"GameRendererDrsMixin",
			"RenderTargetMixin");

	private static final Set<String> CHUNK_MIXINS = Set.of(
			"CompileTaskMixin",
			"CompileTaskQueueMixin",
			"SectionTaskQueueMixin");

	private boolean vulkan;
	private boolean sodium;

	@Override
	public void onLoad(String mixinPackage) {
		vulkan = ModProbe.vulkanMod();
		sodium = ModProbe.sodium();

		if (vulkan) {
			HLog.LOG.info("Hydrogen: VulkanMod detected, framebuffer scaling disabled");
		}

		if (sodium) {
			HLog.LOG.info("Hydrogen: Sodium detected, vanilla chunk ordering hooks disabled");
		}
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		String simple = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);

		if (vulkan && DRS_MIXINS.contains(simple)) {
			return false;
		}

		// Sodium replaces the vanilla section queue entirely, so these would
		// either never fire or fight its own ordering.
		return !sodium || !CHUNK_MIXINS.contains(simple);
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
