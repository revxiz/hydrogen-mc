package dev.hydrogen.core.hw;

import dev.hydrogen.core.cpu.CpuTopology;

/** The probed machine. Rebuilt when the window or monitor changes. */
public final class HardwareProfile {
	private volatile DisplayInfo display = DisplayInfo.UNKNOWN;
	private volatile GpuInfo gpu = GpuInfo.UNKNOWN;
	private volatile CpuTopology cpu = CpuTopology.flat(Runtime.getRuntime().availableProcessors());
	private volatile int renderDistanceChunks = 12;
	private volatile double fovDegrees = 70.0D;
	private volatile long heapMaxBytes = Runtime.getRuntime().maxMemory();

	public DisplayInfo display() {
		return display;
	}

	public void setDisplay(DisplayInfo display) {
		this.display = display == null ? DisplayInfo.UNKNOWN : display;
	}

	public GpuInfo gpu() {
		return gpu;
	}

	public void setGpu(GpuInfo gpu) {
		this.gpu = gpu == null ? GpuInfo.UNKNOWN : gpu;
	}

	public CpuTopology cpu() {
		return cpu;
	}

	public void setCpu(CpuTopology cpu) {
		if (cpu != null) {
			this.cpu = cpu;
		}
	}

	public int renderDistanceChunks() {
		return renderDistanceChunks;
	}

	public void setRenderDistanceChunks(int chunks) {
		if (chunks > 0) {
			this.renderDistanceChunks = chunks;
		}
	}

	public double fovDegrees() {
		return fovDegrees;
	}

	public void setFovDegrees(double fov) {
		if (fov > 1.0D && fov < 180.0D) {
			this.fovDegrees = fov;
		}
	}

	public long heapMaxBytes() {
		return heapMaxBytes;
	}

	public void refreshHeap() {
		this.heapMaxBytes = Runtime.getRuntime().maxMemory();
	}

	public String describe() {
		return "CPU " + cpu.describe() + " | GPU " + gpu.describe() + " | " + display.describe();
	}
}
