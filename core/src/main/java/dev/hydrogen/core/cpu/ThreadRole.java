package dev.hydrogen.core.cpu;

/** Scheduling class a game thread is placed in. */
public enum ThreadRole {
	/** Client render thread and the main game loop. */
	RENDER,
	/** Integrated server tick loop. */
	SERVER,
	/** Chunk meshing and section build workers. */
	CHUNK_BUILD,
	/** Worldgen, resource loading, network IO and other latency-tolerant work. */
	BACKGROUND
}
