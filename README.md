# Hydrogen

Most performance mods ship a set of numbers someone picked on their own PC. Cap
the frame time at 16ms, evict textures at 2GB, cull anything past 64 blocks.
Those numbers are right for exactly one machine.

Hydrogen doesn't ship any. It asks your OS what CPU you have, asks the driver how
much video memory exists, asks GLFW what your monitor runs at, then benchmarks
your actual game for five seconds and works out its own thresholds from what it
measured. A 4K 60Hz rig and a 1080p 240Hz rig get completely different settings
without anyone touching a config file.

Client side only. Fabric.

## Download

| Jar | Minecraft | Java | Fabric Loader |
|---|---|---|---|
| [1.20.x](../../raw/main/dist/hydrogen-mc1.20.1-1.0.0.jar) | 1.20 - 1.20.6 | 17+ | 0.16.0+ |
| [1.21 - 1.21.1](../../raw/main/dist/hydrogen-mc1.21.1-1.0.0.jar) | 1.21 - 1.21.1 | 21+ | 0.16.0+ |
| [1.21.9 - 1.21.11](../../raw/main/dist/hydrogen-mc1.21.11-1.0.0.jar) | 1.21.9 - 1.21.11 | 21+ | 0.16.0+ |
| [26.1+](../../raw/main/dist/hydrogen-mc26.2-1.0.0.jar) | 26.1+ | 25+ | 0.19.3+ |

Grab the one matching your version, drop it in `mods/`, done. Fabric API is the
only dependency.

## What it actually does

### It puts your threads on the right cores

At startup Hydrogen asks the operating system to describe your CPU properly.
Windows via `GetLogicalProcessorInformationEx`, Linux via sysfs, macOS via
sysctl. That gives real physical cores, which logical CPUs are hyperthread
siblings, and on Intel 12th gen and newer, which cores are P and which are E.

The render thread gets pinned to your fastest physical cores. Chunk meshing and
background pools get everything else. The point is that a chunk build should
never land on the same core as the frame you're waiting on. How many cores the
frame path claims scales with what you have: one on a dual core, four on a
16-core desktop.

### It asks for clocks when frames get tight

Your monitor decides the target. 144Hz means 6.94ms per frame, 60Hz means
16.7ms. When frames start overrunning that, Hydrogen switches the Windows power
plan to High Performance and stops the system dropping into idle states. On
Linux it writes `performance` to the cpufreq governor. Everything gets put back
when frames settle down, and again on exit.

Laptops on battery are left alone by default, because nobody wants a mod
flattening their battery to gain four frames.

If your machine genuinely can't hit your panel's refresh rate, Hydrogen notices
during calibration and steps the target down through refresh divisors instead.
On a 144Hz monitor that means aiming at 72, or 48 if 72 is still out of reach.
Chasing a number you can't hit just pins the CPU at full clocks forever and
parks the resolution scaler at its floor for no benefit.

### It moves garbage collection out of your way

Hydrogen listens to the JVM's GC notifications and pulls collections forward
into moments you won't notice: standing still, inventory open, game paused. If
you've swung a sword in the last six seconds, it won't touch anything.

How full the heap has to get before a sweep is worth it comes from the
allocation rate measured during calibration. A heavy modpack burning 500MB a
second sweeps a lot earlier than a light one.

### It scales the world, not the HUD

When the GPU falls behind or video memory fills up, Hydrogen renders the 3D world
into a smaller buffer and scales it back up. Your HUD, your text, your crosshair
and every menu are drawn afterwards at full native resolution, so they stay
sharp. This is the difference between dynamic resolution and just turning your
monitor down.

You set the floor. `drs.minScale=0.70` means it will never go below 70%, no
matter how bad things get. Auto-tuning picks everything else, including the step
size, which is finer at 4K than at 1080p because each step frees more pixels.

### It clears video memory before the driver panics

Total and free VRAM come from the driver through `GL_NVX_gpu_memory_info` on
NVIDIA or `GL_ATI_meminfo` on AMD. If neither answers, every memory feature
switches itself off rather than inventing a limit.

Thresholds follow the card. A 2GB card starts clearing at 85%, a 12GB card at
94%. When it fires, single-file textures get released, and the game re-uploads
them next time they're needed. Block and item atlases are never touched because
the game can't rebuild those on demand. If the driver reports it's already
spilling to system RAM, render distance drops too, and comes back once there's
room.

### It stops drawing things smaller than a pixel

For every entity, Hydrogen works out how tall it lands on screen:

```
pixels = size / distance * (viewportHeight / (2 * tan(fov / 2)))
```

Under one physical pixel, the draw call never reaches the driver. The viewport
height it uses is the live one including any active scaling, and the threshold
follows your OS DPI setting, so 150% Windows scaling needs 1.5 device pixels
before something counts.

### It builds chunks where you're looking

Your yaw, pitch and movement direction make a forward vector. Sections inside a
60 degree cone keep their normal priority. Everything outside gets pushed back,
so the terrain in front of you finishes first.

On 1.21.9 and newer this is one redirect of the distance the game's own queue
already sorts by. The recompile quota and cancellation logic stay exactly as
Mojang wrote them. On older versions, distant sections behind you also get their
rebuild postponed during bad frames, and always replayed once things recover.
Nothing is silently dropped.

## The five second benchmark

The first time you load a world, Hydrogen throws away one second of loading
frames and then watches the next five with every adaptive feature switched off.
It records frame time percentiles, jitter, allocation rate and video memory
growth, then derives its thresholds from those. Nothing is drawn while it runs
and no setting changes. It runs again if you change resolution or move to a
different monitor.

You'll see two lines in your log:

```
Hydrogen: NVIDIA GeForce GT 1030 (2.0 GB via NVX_gpu_memory_info) | 2560x1440 @ 144Hz
Hydrogen: baseline p50 7.72ms p95 19.07ms jitter 7.46ms churn 569MB/s headroom 0.36x
Hydrogen: tuned to target 13.89ms stall 18.75ms | drs 0.70-1.00 step 0.040 | vram evict 85%
```

That's a real GT 1030 at 1440p. Headroom of 0.36 says it's nowhere near 144Hz,
so the target moved to 72 and resolution scaling took over from there.

## Config

`config/hydrogen.properties`, written on first launch. Nearly everything says
`auto`, which means Hydrogen works it out. Put a number in and that one value is
pinned while the rest keep tuning themselves.

The ones people actually change:

```properties
drs.minScale=0.70                       # never scale below this
target.frameTimeMs=auto                 # set 16.7 to just target 60fps
cpu.governor.allowPowerPlanSwitch=auto  # auto means AC only, never on battery
gc.enabled=true
log.verbose=false                       # log every tuning decision
```

## Running with other mods

Hydrogen is built to sit underneath everything else. It never replaces a
renderer, a scheduler or a shader.

With **Sodium** installed, the vanilla chunk ordering hooks switch themselves off
because Sodium has its own scheduler. Everything else still applies. With
**VulkanMod**, resolution scaling switches off because there's no GL framebuffer
to redirect, but CPU tuning, GC timing and sub-pixel culling carry on. **Iris**
needs no special handling. **C2ME** doesn't overlap at all, since it threads
server-side chunk generation while Hydrogen reorders client-side meshing.

## When things don't work

None of this is required for the game to run. Every OS call is best effort and
returns false instead of throwing.

If thread pinning is denied, or you have more than 64 logical CPUs, it falls back
to normal JVM thread priorities. If the power governor isn't writable, which is
the normal case on Linux without root, it holds one spare core out of deep sleep
instead, which lifts clocks without needing privileges. No VRAM extension means
memory features stay off. `-XX:+DisableExplicitGC` in your launch arguments means
GC coordination drops to reporting only, so remove it if you want that feature.
On macOS there's no thread affinity API at all, so pinning is skipped and only
priorities apply, though topology detection still works.

Anything that fails is logged once, not once per frame.

## Known limits

Resolution scaling on 1.21.9+ and 26.x runs on the new Blaze3D `GpuDevice` API,
which is still moving between snapshots. The code is there but it's opt-in behind
`drs.allowNewBlaze3d=true`. On 1.20.x and 1.21.1 the OpenGL path is on by
default. If you try the new one, please open an issue either way.

26.x reworked how sections are invalidated and no longer exposes a public
per-section dirty call, so chunk deferral isn't available there. Cone
prioritisation still is.

Fabulous graphics uses extra full-resolution render targets during the world
pass, and resolution scaling doesn't mix with those. Leave scaling off if you use
Fabulous.

AMD's `ATI_meminfo` reports free memory but never total, so capacity is taken
from the first reading before the world loads.

There's no in-game settings screen yet. GUI code changes a lot between these four
Minecraft versions and it would have doubled the surface area for something
cosmetic.

## Building

```bash
./gradlew build          # every branch
./gradlew :mc1_21_1:build
./gradlew collectJars    # all four jars into build/dist
```

You need JDK 25. It compiles the older branches with `--release 17` and `21`.

```
core/       plain Java. No Minecraft, no dependencies. Policy and maths.
mcshared/   LWJGL and Fabric Loader only. Native calls, probes, entrypoints.
mccommon/   Minecraft APIs identical on all four branches.
mclegacy/   1.20.1 + 1.21.1   (OpenGL render targets)
mcmodern/   1.21.11 + 26.2    (Blaze3D GpuDevice)
versions/   whatever is left that genuinely differs
```

Hydrogen bundles no libraries. Native calls go through LWJGL's loader, which the
game already ships, which is why the jar is about 130KB.

## Licence

MIT. Do what you like with it.
