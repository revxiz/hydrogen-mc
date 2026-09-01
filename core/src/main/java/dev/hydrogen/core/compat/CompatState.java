package dev.hydrogen.core.compat;

/** What Hydrogen found alongside it at startup. Written once during pre-launch. */
public final class CompatState {
	private volatile RenderBackend backend = RenderBackend.UNKNOWN;
	private volatile boolean sodium;
	private volatile boolean vulkanMod;
	private volatile boolean iris;
	private volatile String gpuVendor = "unknown";
	private volatile String gpuRenderer = "unknown";

	public RenderBackend backend() {
		return backend;
	}

	public void setBackend(RenderBackend backend) {
		this.backend = backend;
	}

	public boolean sodium() {
		return sodium;
	}

	public boolean vulkanMod() {
		return vulkanMod;
	}

	public boolean iris() {
		return iris;
	}

	public void setMods(boolean sodium, boolean vulkanMod, boolean iris) {
		this.sodium = sodium;
		this.vulkanMod = vulkanMod;
		this.iris = iris;
	}

	public String gpuVendor() {
		return gpuVendor;
	}

	public String gpuRenderer() {
		return gpuRenderer;
	}

	public void setGpu(String vendor, String renderer) {
		this.gpuVendor = vendor == null ? "unknown" : vendor;
		this.gpuRenderer = renderer == null ? "unknown" : renderer;
	}

	/** Framebuffer tricks only work on the OpenGL path. */
	public boolean allowFramebufferScaling() {
		return backend.isGl() && !vulkanMod;
	}

	public String describe() {
		StringBuilder sb = new StringBuilder(backend.name().toLowerCase());

		if (sodium) {
			sb.append(" +sodium");
		}

		if (vulkanMod) {
			sb.append(" +vulkanmod");
		}

		if (iris) {
			sb.append(" +iris");
		}

		return sb.toString();
	}
}
