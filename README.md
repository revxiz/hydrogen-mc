<div align="center">

<img src="mcshared/resources/assets/hydrogen/icon.png" width="112" alt="Hydrogen icon">

# Hydrogen

**A Minecraft performance mod that measures your machine instead of guessing.**

[![Fabric](https://img.shields.io/badge/Fabric-supported-dbd0b4?style=flat-square)](#download)
[![Quilt](https://img.shields.io/badge/Quilt-supported-9722ff?style=flat-square)](#download)
[![Forge](https://img.shields.io/badge/Forge-supported-e04e14?style=flat-square)](#download)
[![NeoForge](https://img.shields.io/badge/NeoForge-supported-f16436?style=flat-square)](#download)
<br>
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1_to_26.3-62b47a?style=flat-square)](#download)
[![Build](https://img.shields.io/github/actions/workflow/status/revxiz/hydrogen-mc/build.yml?branch=main&style=flat-square&label=build)](../../actions/workflows/build.yml)
[![Licence](https://img.shields.io/badge/licence-MIT-blue?style=flat-square)](LICENSE)

</div>

Most performance mods ship numbers someone picked on their own PC. Cap the frame
time at 16 ms, evict textures at 2 GB, cull anything past 64 blocks. Those
numbers are right for one machine.

Hydrogen ships none of them. It asks your OS what CPU you have, asks the driver
how much video memory exists, asks the window system (GLFW, or SDL3 from 26.3)
what your monitor runs at, then watches your game for a few seconds and works
out its own thresholds from what it saw. A
4K 60 Hz desktop and a 1080p 240 Hz laptop end up with different settings, and
nobody opens a config file.

It needs nothing else installed. No Fabric API, no Quilt Standard Libraries, no
config library.

## Download

Pick your loader and Minecraft version, put the jar in `mods/`, and that's it.

| Minecraft | Fabric | Quilt | Forge | NeoForge |
|---|:---:|:---:|:---:|:---:|
| **26.3** | [jar](dist/fabric/hydrogen-fabric-26.3-1.1.0.jar) | [jar](dist/quilt/hydrogen-quilt-26.3-1.1.0.jar) | [jar](dist/forge/hydrogen-forge-26.3-1.1.0.jar) | [jar](dist/neoforge/hydrogen-neoforge-26.3-1.1.0.jar) ¹ |
| **26.2** | [jar](dist/fabric/hydrogen-fabric-26.2-1.1.0.jar) | [jar](dist/quilt/hydrogen-quilt-26.2-1.1.0.jar) | [jar](dist/forge/hydrogen-forge-26.2-1.1.0.jar) | [jar](dist/neoforge/hydrogen-neoforge-26.2-1.1.0.jar) |
| **26.1 to 26.1.2** | [jar](dist/fabric/hydrogen-fabric-26.1-1.1.0.jar) | [jar](dist/quilt/hydrogen-quilt-26.1-1.1.0.jar) | [jar](dist/forge/hydrogen-forge-26.1-1.1.0.jar) ² | [jar](dist/neoforge/hydrogen-neoforge-26.1-1.1.0.jar) ² |
| **1.21.11** | [jar](dist/fabric/hydrogen-fabric-1.21.11-1.1.0.jar) | [jar](dist/quilt/hydrogen-quilt-1.21.11-1.1.0.jar) | [jar](dist/forge/hydrogen-forge-1.21.11-1.1.0.jar) | [jar](dist/neoforge/hydrogen-neoforge-1.21.11-1.1.0.jar) |
| **1.21 and 1.21.1** | [jar](dist/fabric/hydrogen-fabric-1.21.1-1.1.0.jar) | [jar](dist/quilt/hydrogen-quilt-1.21.1-1.1.0.jar) | [jar](dist/forge/hydrogen-forge-1.21.1-1.1.0.jar) ² | [jar](dist/neoforge/hydrogen-neoforge-1.21.1-1.1.0.jar) ² |
| **1.20 to 1.20.4** | [jar](dist/fabric/hydrogen-fabric-1.20.1-1.1.0.jar) | [jar](dist/quilt/hydrogen-quilt-1.20.1-1.1.0.jar) | [jar](dist/forge/hydrogen-forge-1.20.1-1.1.0.jar) ² | use the Forge jar ³ |

¹ NeoForge for 26.3 is still in beta.
² Forge and NeoForge jars are built for the exact patch release shown in the file
name (26.1.2, 1.21.1, 1.20.1), because those loaders run on Mojang's own names.
³ NeoForge for 1.20.1 is Forge 47 under a new name and loads the Forge jar as is.

| Loader | Minimum version | Java |
|---|---|---|
| Fabric Loader | 0.16.0 (0.19.3 on 26.x) | 17 for 1.20, 21 for 1.21, 25 for 26.x |
| Quilt Loader | 0.26.0 (0.30.1 on 26.x) | same |
| Forge | 47 / 52 / 61 / 64 / 65 / 66 | same |
| NeoForge | 21.1 / 21.11 / 26.1.2 / 26.2 / 26.3 | same |

Hydrogen is a client mod. The two simulation options at the bottom of this page
also work on a server, but nothing needs to be installed there for the client
features, and the client never needs it on the server.

## What it actually does

### It keeps the render thread's cores to itself

At start-up Hydrogen asks the operating system to describe your CPU properly:
Windows through `GetLogicalProcessorInformationEx`, Linux through sysfs, macOS
through sysctl. That gives real physical cores, which logical CPUs are
hyperthread siblings, which cores are P and which are E on hybrid Intel parts,
how big each last-level cache is, and the firmware's own ranking of its best
cores.

The render thread gets the best physical cores inside one cache domain. On a
Ryzen X3D chip that means the V-Cache CCD, even though the other one clocks
higher, because Minecraft cares far more about cache than about a few hundred
MHz. Everything else is moved off those cores and off their hyperthread
siblings: the game's worker pool, the integrated server, Sodium's chunk
builders, other mods' threads. A chunk build should never land on the core
drawing the frame you're waiting for. How many cores the frame path claims
scales with what you have, from one on a dual core with hyperthreading to four
on a big desktop.

On macOS, which has no thread pinning, the same roles become quality of service
classes, which is how Apple Silicon decides between performance and efficiency
cores.

### It asks for clocks when frames get tight

Your monitor sets the target. 144 Hz means 6.94 ms per frame, 60 Hz means
16.7 ms. When frames start overrunning that, Hydrogen switches Windows to the
High Performance power plan, or writes `performance` to the Linux cpufreq
governor, and puts it back when frames settle down. On Windows it also opts the
game out of EcoQoS power throttling for as long as it runs.

Two safety nets: a laptop on battery is left alone, and if you unplug while
boosted, the plan goes back straight away. If the game crashes or gets killed
while boosted, the next launch notices and restores your old plan or governor,
unless you changed it yourself in the meantime.

If your machine can't reach your panel's refresh rate, Hydrogen notices during
calibration and aims at a refresh divisor instead. On a 144 Hz monitor that
means 72, or 48 if 72 is still out of reach. Chasing a number you can't hit
just keeps the CPU at full clocks forever and parks resolution at its floor.

### It moves garbage collection out of your way

Hydrogen listens to the JVM's GC notifications and asks for collections in
moments you won't notice: game paused, inventory open, or standing still. If
you've swung a sword or taken a hit in the last six seconds, it waits.

The request runs on a background thread, never the render thread. With a
concurrent collector (ZGC, Shenandoah, or G1 with `ExplicitGCInvokesConcurrent`)
that means the frame doesn't wait at all. With plain G1 a requested collection
stops the world, so Hydrogen then only uses paused and menu time, not the
moments you're standing in the world looking around.

How full the heap has to get first comes from the allocation rate measured
during calibration. A heavy modpack burning 500 MB a second collects a lot
earlier than a light one.

### It scales the world, not the HUD

When the GPU falls behind or video memory fills up, Hydrogen renders the 3D
world into a smaller target and scales it back up. Your HUD, text, crosshair and
menus are drawn afterwards at full resolution, so they stay sharp.

For the length of the world render the smaller target stands in as the game's
main target, so every pass vanilla draws, including entity outlines and the
sky, lands in the scaled image. You set the floor: `drs.minScale=0.70` means it
never goes below 70%. Everything else is tuned automatically, including the step
size, which is finer at 4K than at 1080p because each step frees more pixels.

Scaling pauses on its own while Fabulous graphics (improved transparency on
newer versions) or an Iris shader pack is active, because both draw through
extra full-size targets of their own.

### It clears video memory before the driver panics

Total and free VRAM come from the driver through `GL_NVX_gpu_memory_info` on
NVIDIA or `GL_ATI_meminfo` on AMD. If neither answers, or the driver reports a
pool too small to be real (software renderers, many integrated GPUs), every
memory feature switches itself off instead of inventing a limit.

Thresholds follow the card. A 2 GB card starts clearing at 85%, a 12 GB card at
94%. When it fires, textures nobody has drawn for a while get released and the
game reloads them the next time they're needed. Atlases are never touched, and
neither are downloaded player skins, since neither can be rebuilt on demand. If
the driver reports it's already spilling into system RAM, render distance is
capped for the session and given back one chunk at a time once there's room.
Your saved render distance is never changed.

### It stops drawing things smaller than a pixel

For every entity and block entity, Hydrogen works out how tall it lands on
screen:

```
pixels = size / distance * (viewportHeight / (2 * tan(fov / 2)))
```

Under one physical pixel, the draw call never reaches the driver. The viewport
height is the live one including any scaling, and the threshold follows your OS
DPI setting, so 150% Windows scaling needs 1.5 device pixels. Size comes from
the same culling box vanilla uses, so scaled-up display entities stay visible,
and anything vanilla never culls, like lightning, is left alone.

To be fair about it: one block only drops under a pixel several hundred blocks
away at 1080p, so in normal play this mostly catches dropped items, arrows and
other small things far off.

### It builds chunks where you're looking

Your camera direction and movement make a forward vector. Sections inside a
60 degree cone keep their normal priority and everything outside gets pushed
back, so the terrain in front of you finishes first. Third person works too,
since the camera is read rather than the player's eyes.

On 1.21.11 and 26.x this is one redirect of the distance the game's own queue
already sorts by, and Mojang's recompile quota and cancellation logic stay as
written. Up to 26.1, distant sections behind you also get their rebuild
postponed during bad frames, and always replayed once things recover.

### It protects your sound pool

Minecraft's audio backend has 247 channels. Once they're full every new sound is
dropped, and vanilla has no idea which ones mattered. A creeper fuse and a
distant cow compete on equal terms.

Hydrogen tiers them. Player and hostile sounds are never culled. Ambience, music
and weather go first, and only once the pool is under pressure, which defaults
to 75% full. Below that nothing is touched.

### It skips collision for particles behind your head

Every live particle runs a block collision sweep each tick, on screen or not.
For particles behind the camera that sweep is skipped, but they keep moving and
ageing normally, so turning around shows them where they should be.

### It can thin distant mob AI and idle hoppers

Two options that also work on servers, both off by default because they change
simulation rather than presentation.

Passive mobs beyond 48 blocks with no player nearby can run their AI one tick in
four. Hostile mobs, anything with a target, anything riding or ridden, and
villagers and wandering traders are never touched, since iron farms and trading
halls depend on them. Movement and collision still run every tick.

Empty hoppers can have their search for dropped items thinned the same way.
Pulling from a chest above, pushing, cooldowns and the tick ordering that hopper
clocks rely on are all left to vanilla. The only difference is that an item
lying on top of an empty hopper can wait up to three extra ticks.

Turn them on with `ai.throttle.enabled=true` and `hopper.throttle.enabled=true`.

## The calibration pass

When you join a world, Hydrogen waits for the loading screen to close, skips two
seconds while the first chunks mesh, then watches five seconds of play with
every adaptive feature held still. Time spent with a menu open doesn't count. It
records frame time percentiles, jitter, allocation rate and video memory growth,
then derives its thresholds from those. Nothing is drawn and no setting changes
while it runs. It runs again if you change resolution or move to a monitor with
a different refresh rate.

You'll see a few lines in your log:

```
Hydrogen: NVIDIA GeForce GT 1030 (2.0 GB via NVX_gpu_memory_info) | 2560x1440 @ 144Hz
Hydrogen: baseline p50 7.72ms p95 19.07ms jitter 7.46ms churn 569MB/s headroom 0.36x
Hydrogen: tuned to target 13.89ms stall 18.75ms | drs 0.70-1.00 step 0.040 | vram evict 85%
```

That's a GT 1030 at 1440p. Headroom of 0.36 says 144 Hz was never going to
happen, so the target moved to 72 and resolution scaling took it from there.

## Config

`config/hydrogen.properties` is written on first launch. Nearly everything says
`auto`, which means Hydrogen works it out. Put a number in and that one value is
pinned while the rest keep tuning themselves.

The ones people actually change:

```properties
# Never scale the world below this
drs.minScale=0.70
# Set 16.7 to simply target 60 fps
target.frameTimeMs=auto
# auto = allowed on AC power, never on battery
cpu.governor.allowPowerPlanSwitch=auto
gc.enabled=true
# Pool fill before sound culling starts
audio.pressureAt=0.75
particle.cullPhysics=true
# Opt-in, these change mob and hopper behaviour
ai.throttle.enabled=false
hopper.throttle.enabled=false
# Log every tuning decision
log.verbose=false
```

A comment after a value on the same line works too. When a new version adds
settings, they're appended and your values are kept. Hydrogen never writes a
change of its own into this file: if a feature switches itself off after an
error, that only lasts until you restart.

## Two things it deliberately does not do

**Flat lighting for GUI items.** The usual pitch is that switching inventory
items to flat lighting cuts their render cost. It doesn't. `setupForFlatItems`
sets two light vectors as uniforms and the same shader runs either way, so you'd
change how every 3D item looks for no measurable gain. The real cost of GUI item
rendering is draw call count, which ImmediatelyFast already handles properly.

**Object pooling for `Vec3` and `SectionPos`.** Both are immutable and both end
up all over vanilla code: entity fields, packets, hash map keys. Pooling needs to
know when the last reference dies, and there's no way to know that for objects
handed to code you don't control. Recycling a live `Vec3` silently corrupts an
entity position, and recycling a `SectionPos` used as a map key corrupts the map.
On top of that, allocating a 32 byte object is a pointer bump, escape analysis
often removes it entirely, and a young generation collection only pays for what
survives. Hydrogen measures your allocation rate and schedules collections
around it instead.

## Running with other mods

Hydrogen sits underneath everything else. It never replaces a renderer, a
scheduler or a shader.

With **Sodium** (or Embeddium and Rubidium on Forge), the vanilla chunk ordering
hooks switch themselves off because Sodium has its own scheduler. Everything
else still applies, and Sodium's builder threads are kept off the render cores
like any other worker. With **VulkanMod**, resolution scaling switches off but
CPU, GC and culling carry on. **Iris** and **Oculus** work as is; resolution
scaling pauses while a shader pack is on. **C2ME** doesn't overlap at all, since
it threads server-side chunk generation while Hydrogen reorders client-side
meshing.

## When things don't work

None of this is needed for the game to run. Every OS call is best effort and
returns false instead of throwing, and anything that fails is logged once.

If thread pinning is denied, or you have more than 64 logical CPUs on Windows,
Hydrogen falls back to normal JVM thread priorities. If the power governor isn't
writable, which is normal on Linux without root, it holds one spare core out of
deep sleep instead, which lifts clocks without needing privileges. No VRAM
extension means memory features stay off. `-XX:+DisableExplicitGC` in your
launch arguments turns GC coordination into reporting only, so remove it if you
want that feature.

## Known limits

Resolution scaling on 1.21.11 and 26.x runs through Mojang's newer render
abstraction, which still changes between releases, so it's opt-in there behind
`drs.allowNewBlaze3d=true`. On 1.20.x and 1.21.1 the OpenGL path is on by
default. If you try the new one, please open an issue either way.

From 26.2, vanilla no longer exposes a per-section rebuild call, so postponing
rebuilds behind you is not available there. Cone ordering still is.

26.3 can render through Vulkan. Memory readings come from OpenGL extensions, so
on the Vulkan backend the VRAM features stay off; everything else works.

AMD's `ATI_meminfo` reports free memory but never total, so capacity is taken
from the first reading before the world loads.

There's no in-game settings screen yet, only the config file.

## Building

```bash
./gradlew :core:test                        # unit tests, no game needed
./gradlew collectJars                       # every jar into build/dist
./gradlew collectJars -Ploader=fabric       # one loader
./gradlew collectJars -Pmc=26.3             # one Minecraft branch
./gradlew :neoforge-1.21.1:build            # one jar
```

You need JDK 25 to run Gradle. Each branch compiles with its own toolchain (17,
21 or 25); Gradle finds installed JDKs and downloads missing ones.

<details>
<summary><b>How the source is laid out</b></summary>

```
core/                 plain Java. Policy, maths and the platform interface. Unit tested.
mcshared/             LWJGL only: native calls, GPU and display probes, the boot path.
mccommon/             Minecraft code that is identical on every version.
mclegacy/             1.20.1 and 1.21.1   (OpenGL render targets)
mcmodern/             1.21.11 to 26.3     (Blaze3D and renderpearl)
loaders/<loader>/     entry point, loader bridge and metadata for each loader
versions/<mc>/common/ the Minecraft code that still differs per version
versions/<mc>/<loader>/   one Gradle project per jar
gradle/               the shared build scripts, one per loader
```

Every loader jar compiles the same sources. The only loader-specific code is a
small entry class and a bridge that answers three questions: where the config
folder is, which other mods are loaded, and whether this is a client. All of
Hydrogen's hooks are mixins, which every loader supports, so there is no event
API to port.

| Loader | Build tool |
|---|---|
| Fabric and Quilt | Fabric Loom |
| NeoForge | ModDevGradle |
| Forge 1.21 and later | ForgeGradle 7 |
| Forge 1.20.1 | ModDevGradle (legacy Forge) |

</details>

Hydrogen bundles no libraries. Native calls go through LWJGL, which the game
already ships, so each jar is about 180 KB.

## Licence

MIT. Do what you like with it.
