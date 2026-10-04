package dev.hydrogen.mc;

import dev.hydrogen.core.Hydrogen;
import dev.hydrogen.core.hw.Tuning;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * Sub-pixel test for entities, shared by every version's EntityRenderer hook.
 *
 * Size comes from the culling box vanilla itself uses, not the collision box.
 * Display entities scaled up by a datapack and lightning bolts both have tiny or
 * empty collision boxes but large visuals; vanilla exempts the latter, and an
 * empty box is never culled. Glowing entities are left alone because their
 * outline stays visible through walls at any size.
 */
public final class EntityCulling {
	private EntityCulling() {
	}

	/**
	 * @param box culling box from the entity or its renderer, may be null
	 * @return true when the entity can be skipped this frame
	 */
	public static boolean cull(Entity entity, AABB box, double camX, double camY, double camZ) {
		Hydrogen h = Hydrogen.get();

		if (h == null || box == null) {
			return false;
		}

		Tuning t = h.tuning();

		if (!t.enabled() || !t.subPixel() || entity.isCurrentlyGlowing()) {
			return false;
		}

		double sx = box.maxX - box.minX;
		double sy = box.maxY - box.minY;
		double sz = box.maxZ - box.minZ;
		double size = Math.max(sx, Math.max(sy, sz));

		if (!(size > 0.0D) || Double.isInfinite(size)) {
			return false;
		}

		double dx = (box.minX + box.maxX) * 0.5D - camX;
		double dy = (box.minY + box.maxY) * 0.5D - camY;
		double dz = (box.minZ + box.maxZ) * 0.5D - camZ;

		return h.culler().shouldCullSq(size, dx * dx + dy * dy + dz * dz);
	}
}
