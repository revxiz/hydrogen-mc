package dev.hydrogen.mc.chunk;

import dev.hydrogen.core.Hydrogen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;

/**
 * Cone-weighted replacement for the distance metric the section queue sorts by.
 *
 * The vanilla queue already picks the nearest pending section and balances new
 * builds against recompiles with its own quota. Only the distance it compares is
 * changed, so meshing shifts into the forward sight cone while all of that
 * bookkeeping stays exactly as Mojang wrote it.
 */
public final class ConeMetric {
	private ConeMetric() {
	}

	public static double weighted(BlockPos origin, Position camera) {
		double base = origin.distToCenterSqr(camera);
		Hydrogen h = Hydrogen.get();

		if (h == null || !h.enabled() || !h.cone().enabled()) {
			return base;
		}

		double m = h.cone().costMultiplier(origin.getX() + 8.0D, origin.getY() + 8.0D, origin.getZ() + 8.0D);
		// The queue compares squared distances, so the multiplier is squared too.
		return base * m * m;
	}
}
