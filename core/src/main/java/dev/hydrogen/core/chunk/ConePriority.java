package dev.hydrogen.core.chunk;

import dev.hydrogen.core.config.HConfig;
import dev.hydrogen.core.hw.Budget;

/**
 * Orders chunk meshing by where the player is looking and heading.
 *
 * Sections inside the forward sight cone keep their plain distance cost.
 * Everything outside is penalised, which pushes it behind the cone in any
 * distance-ordered queue without starving it. The penalty scales with worker
 * count and render distance, so a fast machine barely reorders and a slow one
 * reorders hard.
 */
public final class ConePriority {
	private final HConfig config;
	private final Budget budget;

	private volatile double camX;
	private volatile double camY;
	private volatile double camZ;
	private volatile double lookX;
	private volatile double lookY;
	private volatile double lookZ = 1.0D;
	private volatile double velX;
	private volatile double velZ;
	private volatile double speed;

	private volatile long deferred;
	private volatile long insideCone;
	private volatile long outsideCone;

	public ConePriority(HConfig config, Budget budget) {
		this.config = config;
		this.budget = budget;
	}

	/** Called once per frame from the render thread. */
	public void updateCamera(double x, double y, double z,
			double yawDegrees, double pitchDegrees,
			double vx, double vz) {
		this.camX = x;
		this.camY = y;
		this.camZ = z;

		double yaw = Math.toRadians(yawDegrees);
		double pitch = Math.toRadians(pitchDegrees);
		double cosPitch = Math.cos(pitch);

		// Minecraft yaw grows clockwise starting at -Z.
		this.lookX = -cosPitch * Math.sin(yaw);
		this.lookY = -Math.sin(pitch);
		this.lookZ = cosPitch * Math.cos(yaw);

		this.velX = vx;
		this.velZ = vz;
		this.speed = Math.sqrt(vx * vx + vz * vz);
	}

	public boolean enabled() {
		return config.bool("chunk.cone.enabled");
	}

	public double coneCos() {
		return Math.cos(Math.toRadians(budget.coneDegrees() * 0.5D));
	}

	/** Cosine between the camera forward vector and the direction of a section. */
	public double forwardness(double x, double y, double z) {
		double dx = x - camX;
		double dy = y - camY;
		double dz = z - camZ;
		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);

		if (len < 1.0E-4D) {
			return 1.0D;
		}

		double cos = (dx * lookX + dy * lookY + dz * lookZ) / len;

		// Movement direction counts too, so strafing does not stall the path ahead.
		if (speed > 0.05D) {
			cos = Math.max(cos, (dx * velX + dz * velZ) / (len * speed) * 0.85D);
		}

		return cos;
	}

	/**
	 * Multiplier for a section's existing distance cost: 1.0 inside the cone,
	 * rising toward the derived penalty directly behind the player.
	 */
	public double costMultiplier(double x, double y, double z) {
		if (!enabled()) {
			return 1.0D;
		}

		double cos = forwardness(x, y, z);
		double coneCos = coneCos();

		if (cos >= coneCos) {
			insideCone++;
			return 1.0D;
		}

		outsideCone++;
		double penalty = budget.conePenalty();
		double t = (coneCos - cos) / (coneCos + 1.0D);
		return 1.0D + (penalty - 1.0D) * t * t;
	}

	/** Cone-weighted cost used to pick the next section out of a queue. */
	public double cost(double x, double y, double z) {
		double dx = x - camX;
		double dy = y - camY;
		double dz = z - camZ;
		double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
		return dist * costMultiplier(x, y, z);
	}

	/**
	 * True when a distant section behind the player should wait for a calmer
	 * frame. Nearby work is never deferred, so the player is not ringed by holes.
	 */
	public boolean shouldDefer(double x, double y, double z, double frameMs) {
		if (!enabled() || !config.bool("chunk.cone.deferBehind")) {
			return false;
		}

		if (frameMs < budget.coneDeferFrameMs()) {
			return false;
		}

		double dx = x - camX;
		double dy = y - camY;
		double dz = z - camZ;
		double near = 64.0D;

		if (dx * dx + dy * dy + dz * dz < near * near) {
			return false;
		}

		if (forwardness(x, y, z) >= 0.0D) {
			return false;
		}

		deferred++;
		return true;
	}

	public long deferredCount() {
		return deferred;
	}

	public double coneShare() {
		long total = insideCone + outsideCone;
		return total == 0L ? 0.0D : (double) insideCone / total;
	}
}
