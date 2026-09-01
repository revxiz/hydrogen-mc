package dev.hydrogen.core.gpu;

/**
 * VRAM reading in kilobytes as reported by the driver.
 *
 * @param totalKb  dedicated video memory, 0 when the driver will not say
 * @param freeKb   currently available video memory
 * @param evictedKb bytes the driver has already spilled to system RAM
 * @param source   extension the numbers came from
 */
public record VramSnapshot(long totalKb, long freeKb, long evictedKb, String source) {
	public static final VramSnapshot UNKNOWN = new VramSnapshot(0L, 0L, 0L, "none");

	public boolean known() {
		return totalKb > 0L;
	}

	public long usedKb() {
		return Math.max(0L, totalKb - freeKb);
	}

	public double usedPercent() {
		return totalKb > 0L ? 100.0D * usedKb() / totalKb : 0.0D;
	}
}
