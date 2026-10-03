package dev.hydrogen.core.hw;

/**
 * Every value a per-object hook reads, resolved once.
 *
 * Entity, particle, sound and chunk-queue hooks run thousands of times a frame.
 * Reading the config there costs a synchronized Properties lookup and a parse
 * per call, so {@link Budget#refresh()} folds config, hardware and calibration
 * into this record at the control rate (about 20 Hz) and hooks read plain fields.
 */
public record Tuning(
		boolean enabled,
		boolean subPixel,
		double subPixelThreshold,
		double subPixelMinDistance,
		boolean blockEntityCull,
		boolean particleCull,
		boolean particleBehindOnly,
		double particleMinDistanceSq,
		boolean audio,
		int audioPool,
		double audioPressure,
		double audioCullDistanceSq,
		boolean cone,
		double coneCos,
		double conePenalty,
		boolean coneDefer,
		double coneDeferFrameMs,
		boolean aiThrottle,
		double aiDistance,
		int aiInterval,
		boolean hopperThrottle,
		int hopperInterval,
		double targetFrameMs) {

	/** Everything off. Used before the config has been read. */
	public static final Tuning OFF = new Tuning(
			false,
			false, 1.0D, 16.0D,
			false,
			false, true, 256.0D,
			false, 247, 0.75D, 4096.0D,
			false, 0.866D, 1.0D, false, 1000.0D,
			false, 48.0D, 4,
			false, 4,
			16.67D);
}
