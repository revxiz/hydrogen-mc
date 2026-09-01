# Hydrogen

A Fabric client mod that measures your machine and then tunes the game to it. No
fixed resolution targets, no assumed VRAM ceiling, no hardcoded frame budget.

Supported: **1.20.x, 1.21.x, 26.x**. One jar per branch.

| Jar | Minecraft | Java | Fabric Loader |
|---|---|---|---|
| `hydrogen-mc1.20.1` | 1.20 - 1.20.6 | 17+ | 0.16.0+ |
| `hydrogen-mc1.21.1` | 1.21 - 1.21.1 | 21+ | 0.16.0+ |
| `hydrogen-mc1.21.11` | 1.21.9 - 1.21.11 | 21+ | 0.16.0+ |
| `hydrogen-mc26.2` | 26.1+ | 25+ | 0.19.3+ |

Requires Fabric API. Client only. The loader floor is only high on 26.x, where
the unobfuscated game needs a loader that understands it.

## What it does

### Hardware-aware CPU and OS governance

**Topology-aware core binding.** At launch Hydrogen asks the OS to describe the
CPU: `GetLogicalProcessorInformationEx` on Windows, sysfs on Linux, sysctl
`perflevel` on macOS. That gives real physical cores, SMT siblings and the
performance/efficiency split on hybrid parts. The render thread is pinned to the
fastest physical cores, and meshing and background pools get everything the frame
path does not own, so a chunk build can never preempt a frame.

How many cores the frame path gets scales with what is actually there: one on a
dual core, four on a 16-core desktop.

**Dynamic frequency spiking.** The stall threshold is `1000 / refresh rate`,
widened by the frame jitter measured on your machine. On a 60 Hz panel that lands
near 16.7 ms; on a 165 Hz panel near 6 ms. When the 95th percentile frame time
crosses it, Hydrogen switches the Windows power plan to High Performance and
holds the system out of idle states, or writes `performance` to the Linux cpufreq
governor. It reverts once frames settle, and on shutdown.

Laptops on battery are left alone unless you opt in.

**GC-pause synchronisation.** Hydrogen listens to JVM garbage collection
notifications and pulls collections forward into moments you will not feel them:
standing still, an open inventory or chest, a paused game. Combat locks
collection out entirely. The heap level that makes a sweep worthwhile comes from
the allocation rate measured during calibration, so a heavy modpack sweeps
earlier than a light one.

### Adaptive VRAM and render scaling

**Decoupled dynamic resolution scaling.** The 3D world is drawn into an
off-screen target and blitted back up. The HUD, text, crosshair and every menu
are drawn after that, so they stay at native resolution at any scale. Step size
comes from your framebuffer size: finer steps at 4K, coarser at 1080p.

Your floor is respected absolutely. `drs.minScale=0.70` means auto-tuning will
never go below 70%, whatever the pressure.

**VRAM evictor.** Total and free video memory are read from the driver through
`GL_NVX_gpu_memory_info` or `GL_ATI_meminfo`. If neither answers, every
memory-driven feature switches itself off rather than guessing. Thresholds scale
with the card: a 2 GB card starts evicting at 85%, a 12 GB card at 94%.

A pass releases single-file textures, which the game re-uploads lazily on next
use. Atlases are never touched because they cannot be rebuilt on demand. When the
driver reports it is already spilling to system RAM, Hydrogen also trims render
distance and drops resolution, restoring both once there is headroom again.

**Sub-pixel geometry pruning.** For each entity, projected on-screen height is
`size / distance * (viewportHeight / (2 * tan(fov / 2)))`. Below one physical
pixel the draw call is dropped before the driver sees it. The viewport height
used is the live one including any active DRS scale, and the pixel threshold
scales with your OS DPI factor, so a 150% Windows scale needs 1.5 device pixels
to count.

### Universal rendering compatibility

**Zero-conflict instrumentation.** Hydrogen runs from Fabric's `preLaunch`
entrypoint, before any Minecraft class loads, and a Mixin config plugin decides
at class-load time which hooks are safe. With VulkanMod installed, framebuffer
scaling turns itself off. With Sodium installed, the vanilla chunk ordering hooks
turn themselves off. Hydrogen reads metrics around both and never replaces their
shaders or pipelines.

**Motion-vector chunk prioritisation.** Player yaw, pitch and movement velocity
build a forward vector. Sections inside a 60 degree cone keep their plain distance
cost; everything outside is penalised, which pushes it behind the cone in the
meshing queue. On 1.21.9+ and 26.x this is a single redirect of the distance the
vanilla queue already sorts by, so its recompile quota and cancellation sweep
stay exactly as Mojang wrote them.

On 1.20.x through 1.21.11, distant sections behind the player also have their
rebuild postponed during over-budget frames. Deferred work is always replayed
once frames recover, never dropped.

## Calibration

On entering a world, Hydrogen discards one second of loading frames and then
samples for five seconds with every adaptive feature parked. It records frame time
percentiles, frame jitter, allocation churn and video memory growth, then derives
its thresholds from those numbers. Nothing is drawn and no setting changes while
it runs. It re-runs after a resolution or monitor change.

The result is logged:

```
Hydrogen: baseline p50 8.10ms p95 12.40ms jitter 1.90ms churn 210MB/s headroom 1.35x
Hydrogen: tuned to target 6.06ms stall 7.21ms | drs 0.70-1.00 step 0.040 | vram evict 85% | subpixel 1.00px | cone 6.0x
```

## Configuration

`config/hydrogen.properties`, created on first launch. Most values read `auto`,
meaning Hydrogen derives them. Replace any one with a number to pin it; the rest
keep auto-tuning.

The settings people actually change:

```properties
drs.minScale=0.70          # hard floor for downscaling
drs.enabled=true
cull.subpixel.minPixels=auto
cpu.governor.allowPowerPlanSwitch=auto   # auto = AC only, never on battery
gc.enabled=true
hud.overlay=false
log.verbose=false          # log every tuning decision
```

If YACL or Cloth Config is installed, Hydrogen detects it; the file stays the
source of truth either way.

## Graceful degradation

Nothing here is required for the game to run. Every OS call is best effort and
returns false instead of throwing:

- Thread pinning denied, or more than 64 logical CPUs: falls back to
  `Thread.setPriority`.
- Power governor not writable (Linux without root is the normal case): falls back
  to holding one spare core out of deep sleep, which raises clocks without
  privileges.
- No VRAM extension: memory features stay off.
- `-XX:+DisableExplicitGC` set: GC coordination drops to reporting only.
- macOS: Darwin exposes no thread affinity API, so pinning is skipped and JVM
  priorities are used. Topology detection still works.

Failures are logged once, not per frame.

## Playing alongside other performance mods

Hydrogen is built to sit under whatever else you run. It never replaces a
renderer, a scheduler or a shader.

- **Sodium / Embeddium**: the vanilla chunk ordering hooks disable themselves,
  because Sodium supplies its own section scheduler. Everything else applies.
- **VulkanMod**: framebuffer scaling disables itself, since there is no GL
  framebuffer to redirect. CPU governance, GC coordination and sub-pixel culling
  still work.
- **Iris**: no interaction. Hydrogen scales the viewport the shader pack draws
  into and does not touch the pack.
- **C2ME**: no overlap. C2ME threads server-side chunk generation and IO;
  Hydrogen reorders client-side section meshing and only pins threads it is
  handed. Note that C2ME's natives-math module needs Java 22 or newer, which is a
  stricter requirement than Hydrogen has on any branch.

## Known limits

- Viewport scaling on 1.21.9+ and 26.x rides the new Blaze3D `GpuDevice` API,
  which is still shifting between snapshots. It is implemented but opt-in behind
  `drs.allowNewBlaze3d=true`. On 1.20.x and 1.21.1 the OpenGL path is on by
  default.
- 26.x reworked section invalidation and no longer exposes a public per-section
  dirty call, so chunk deferral is not available there. Cone prioritisation is.
- With Fabulous graphics the world render uses extra native-resolution targets;
  DRS and those do not mix, so leave DRS off if you use Fabulous.
- AMD's `ATI_meminfo` reports free memory but not total, so capacity is taken
  from the first reading before the world loads.

## Building

```bash
./gradlew build          # every branch
./gradlew :mc1_21_1:build
./gradlew collectJars    # all jars into build/dist
```

Requires JDK 25 (it compiles the older branches with `--release 17` and `21`).

Layout:

```
core/       pure Java, no Minecraft, no dependencies. Policy and maths.
mcshared/   LWJGL and Fabric Loader only. Native calls, probes, entrypoints.
mccommon/   Minecraft APIs identical on all four branches.
mclegacy/   1.20.1 + 1.21.1   (OpenGL render targets)
mcmodern/   1.21.11 + 26.2    (Blaze3D GpuDevice)
versions/   the remaining per-version differences
```

Hydrogen bundles no libraries. Native calls go through LWJGL's dynamic loader,
which the game already ships, so the jar stays around 115 KB.

## Licence

MIT.
