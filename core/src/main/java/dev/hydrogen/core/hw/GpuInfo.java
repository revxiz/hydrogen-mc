package dev.hydrogen.core.hw;

import dev.hydrogen.core.compat.RenderBackend;

/**
 * GPU facts read from the live driver. {@code vramTotalKb} is 0 when no
 * extension will report it, and every VRAM feature then stays off rather than
 * guessing a ceiling.
 */
public record GpuInfo(
		String vendor,
		String renderer,
		long vramTotalKb,
		String vramSource,
		RenderBackend backend) {

	public static final GpuInfo UNKNOWN =
			new GpuInfo("unknown", "unknown", 0L, "none", RenderBackend.UNKNOWN);

	public boolean vramKnown() {
		return vramTotalKb > 0L;
	}

	public double vramGb() {
		return vramTotalKb / 1024.0D / 1024.0D;
	}

	/**
	 * Cards this small hit the memory wall long before they run out of shading
	 * throughput, so eviction and DRS get more aggressive defaults.
	 */
	public boolean tightVram() {
		return vramKnown() && vramTotalKb <= 3L * 1024L * 1024L;
	}

	public boolean roomyVram() {
		return vramKnown() && vramTotalKb >= 8L * 1024L * 1024L;
	}

	public String describe() {
		return renderer + (vramKnown() ? String.format(" (%.1f GB via %s)", vramGb(), vramSource) : " (VRAM unknown)");
	}
}
