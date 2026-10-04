package dev.hydrogen.mc.gl;

import dev.hydrogen.core.HLog;
import dev.hydrogen.core.compat.RenderBackend;
import dev.hydrogen.core.gpu.VramSnapshot;
import dev.hydrogen.core.hw.GpuInfo;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Reads video memory from whichever driver extension is present. Nothing is
 * assumed: when no extension answers, {@code totalKb} stays 0 and every
 * VRAM-driven feature switches itself off rather than guessing a ceiling.
 *
 * NVIDIA reports total and free directly. AMD's ATI_meminfo reports free only,
 * so the first reading taken before the world loads is used as the capacity
 * reference.
 *
 * Some drivers answer with numbers that mean nothing for eviction: software
 * rasterizers, and Mesa on integrated GPUs, report a few megabytes of
 * "dedicated" memory while really drawing from system RAM. Treating that as a
 * nearly full card made eviction fire on the title screen, so those readings are
 * rejected and the memory features stay off.
 */
public final class VramProbe {
	// GL_NVX_gpu_memory_info
	private static final int DEDICATED_VIDMEM_NVX = 0x9047;
	private static final int CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
	private static final int EVICTED_MEMORY_NVX = 0x904B;

	// GL_ATI_meminfo
	private static final int TEXTURE_FREE_MEMORY_ATI = 0x87FC;

	/** Smallest dedicated pool worth managing; below this the GPU is sharing system RAM. */
	private static final long MIN_PLAUSIBLE_KB = 512L * 1024L;

	private enum Source {
		NVX,
		ATI,
		NONE
	}

	private final int[] scratch = new int[4];

	private Source source = Source.NONE;
	private boolean probed;
	private long referenceTotalKb;
	private String vendor = "unknown";
	private String renderer = "unknown";
	private String version = "unknown";

	/** Called once the GL context is current. */
	public GpuInfo probe(boolean vulkanBackend) {
		if (vulkanBackend) {
			// A Vulkan context exposes no GL memory extensions; report honestly.
			return new GpuInfo(vendor, renderer, 0L, "none", RenderBackend.VULKAN);
		}

		GLCapabilities caps;

		try {
			caps = GL.getCapabilities();
		} catch (IllegalStateException e) {
			// No OpenGL context on this thread: 26.3 can render through Vulkan.
			HLog.once("vram-nogl", "Hydrogen: no OpenGL context, VRAM features stay off");
			return GpuInfo.UNKNOWN;
		}

		try {
			vendor = str(GL11.GL_VENDOR);
			renderer = str(GL11.GL_RENDERER);
			version = str(GL11.GL_VERSION);

			if (caps.GL_NVX_gpu_memory_info) {
				source = Source.NVX;
			} else if (caps.GL_ATI_meminfo) {
				source = Source.ATI;
			} else {
				source = Source.NONE;
				HLog.once("vram-none",
						"Hydrogen: driver reports no VRAM extension, memory features stay off");
			}

			probed = true;

			if (source != Source.NONE && softwareRenderer(renderer)) {
				source = Source.NONE;
				HLog.once("vram-software", "Hydrogen: software renderer (" + renderer + "), memory features stay off");
			}

			VramSnapshot first = read();

			if (source != Source.NONE && first.totalKb() < MIN_PLAUSIBLE_KB) {
				HLog.once("vram-implausible", "Hydrogen: driver reports only " + first.totalKb() / 1024L
						+ " MB of video memory, treating it as shared memory and leaving memory features off");
				source = Source.NONE;
				first = VramSnapshot.UNKNOWN;
			}

			referenceTotalKb = first.totalKb();

			return new GpuInfo(vendor, renderer, referenceTotalKb, sourceName(), RenderBackend.VANILLA_GL);
		} catch (Throwable t) {
			HLog.warnOnce("vram-probe", "Hydrogen: GPU probe failed", t);
			return GpuInfo.UNKNOWN;
		}
	}

	private String sourceName() {
		return switch (source) {
			case NVX -> "NVX_gpu_memory_info";
			case ATI -> "ATI_meminfo";
			case NONE -> "none";
		};
	}

	private static boolean softwareRenderer(String renderer) {
		String r = renderer.toLowerCase(java.util.Locale.ROOT);
		return r.contains("llvmpipe") || r.contains("softpipe") || r.contains("swiftshader")
				|| r.contains("software rasterizer") || r.contains("basic render") || r.contains("gdi generic");
	}

	private static String str(int name) {
		try {
			String s = GL11.glGetString(name);
			return s == null ? "unknown" : s;
		} catch (Throwable t) {
			return "unknown";
		}
	}

	/** Must be called on the render thread with the context current. */
	public VramSnapshot read() {
		if (!probed || source == Source.NONE) {
			return VramSnapshot.UNKNOWN;
		}

		try {
			if (source == Source.NVX) {
				long total = geti(DEDICATED_VIDMEM_NVX);
				long free = geti(CURRENT_AVAILABLE_VIDMEM_NVX);
				long evicted = geti(EVICTED_MEMORY_NVX);

				if (total <= 0L) {
					return VramSnapshot.UNKNOWN;
				}

				return new VramSnapshot(total, Math.min(free, total), evicted, "NVX");
			}

			// ATI_meminfo returns four values in KB; the first is total free.
			GL11.glGetIntegerv(TEXTURE_FREE_MEMORY_ATI, scratch);
			long free = scratch[0];

			if (free <= 0L) {
				return VramSnapshot.UNKNOWN;
			}

			long total = Math.max(referenceTotalKb, free);
			return new VramSnapshot(total, free, 0L, "ATI");
		} catch (Throwable t) {
			source = Source.NONE;
			HLog.warnOnce("vram-read", "Hydrogen: VRAM query failed, disabling memory features", t);
			return VramSnapshot.UNKNOWN;
		}
	}

	private long geti(int name) {
		scratch[0] = 0;
		GL11.glGetIntegerv(name, scratch);
		return Math.max(0, scratch[0]);
	}

	public boolean usable() {
		return probed && source != Source.NONE;
	}

	public String describe() {
		return renderer + " / " + version + " (" + sourceName() + ")";
	}
}
