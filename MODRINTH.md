# Modrinth listing copy

Everything below is ready to paste. Section 1 is the project settings, section 2
is the page body, section 3 is the changelog for the first version upload.

---

## 1. Project settings

**Name**

```
Hydrogen
```

**Summary** (Modrinth caps this at 256 characters)

```
Performance mod with no hardcoded numbers. It measures your CPU, GPU, monitor and frame times at runtime, then tunes core affinity, clock boosting, GC timing, VRAM eviction and dynamic resolution to your machine specifically.
```

**Categories:** Optimization, Utility

**Client/server:** Client required, server optional (two simulation features work server side)

**Licence:** MIT

**Links**

- Source: `https://github.com/revxiz/hydrogen-mc`
- Issues: `https://github.com/revxiz/hydrogen-mc/issues`

**Suggested tags for the search blurb:** dynamic resolution, FPS, low end,
thread affinity, VRAM, Sodium compatible

---

## 2. Page body

Most performance mods ship numbers somebody picked on their own PC. Cap frame
time at 16ms. Evict textures at 2GB. Cull past 64 blocks. Those numbers are
correct for exactly one machine, and it probably isn't yours.

Hydrogen ships none of them.

It asks Windows or Linux or macOS to describe your CPU. It asks your graphics
driver how much VRAM actually exists. It asks GLFW what your monitor runs at and
what your DPI scaling is. Then it benchmarks your game for five seconds and works
out every threshold from what it measured. A 4K 60Hz rig and a 1080p 240Hz rig
end up with completely different settings and neither of you opens a config file.

### What it does

**Puts your threads on the right cores.** Hydrogen reads your real CPU topology,
including P-core and E-core layout on 12th gen Intel and newer, and hyperthread
siblings everywhere. Your render thread gets pinned to the fastest physical
cores. Chunk meshing gets everything else. A chunk build should never land on the
core drawing the frame you're waiting for.

**Asks for clocks when frames get tight.** Your monitor sets the target: 144Hz
means 6.94ms. When frames overrun it, Hydrogen switches the Windows power plan to
High Performance, or writes `performance` to the Linux cpufreq governor, and puts
it back when things settle. On battery it leaves you alone. And if your machine
genuinely can't reach your panel's refresh rate, it notices and aims at a
sensible divisor instead of chasing a number forever.

**Moves garbage collection out of your way.** Collections get pulled forward into
moments you won't feel them: standing still, inventory open, game paused. Swung a
sword in the last six seconds? It won't touch anything. How full the heap gets
first depends on the allocation rate it measured on your setup.

**Scales the world, not the HUD.** When the GPU falls behind or VRAM fills, the
3D world renders smaller and gets scaled back up. Your HUD, text, crosshair and
menus draw afterwards at full native resolution and stay sharp. You set the
floor and it is respected absolutely: `drs.minScale=0.70` will never go below
70%, whatever happens.

**Clears VRAM before the driver panics.** Free and total memory come straight
from the driver. Thresholds follow the card, so a 2GB GPU starts clearing at 85%
and a 12GB one at 94%. Atlases are never touched, only textures the game can
re-upload on demand. If no driver extension reports memory, the feature switches
itself off instead of guessing.

**Stops drawing things smaller than a pixel.** Entities that project to less than
one physical pixel never reach the driver. The threshold follows your DPI
scaling, so 150% Windows scaling needs 1.5 device pixels before something counts.

**Builds chunks where you're looking.** Your view direction and movement make a
forward vector, and terrain inside a 60 degree cone gets built first. On 1.21.9+
this is one small change to the distance the game's own queue already sorts by,
so Mojang's scheduling logic stays intact.

**Protects your sound pool.** Minecraft has 247 audio channels and no idea which
sounds matter when they run out. Hydrogen tiers them: player and hostile sounds
are never culled, ambience and music go first, and only once the pool is actually
under pressure. Below 75% full nothing is touched.

**Skips block entities and particles you can't see.** Chests and banners past the
sub-pixel threshold stop submitting models. Particles behind the camera skip their
collision sweep while still ageing normally, so they expire exactly on schedule.

**Optionally thins distant simulation.** Two server-side switches, both off by
default: passive mobs beyond 48 blocks can run AI one tick in four, and empty
hoppers can thin their pickup scan. Both thin rather than disable, so farms keep
working and vanilla transfer timing is preserved.

### The five second benchmark

First time you load a world, Hydrogen discards a second of loading frames and
watches the next five with everything adaptive switched off. It records frame
percentiles, jitter, allocation churn and VRAM growth, then derives its
thresholds. Nothing is drawn, nothing changes while it runs, and it re-runs if
you change resolution or monitor.

Real output from a GT 1030 at 1440p:

```
Hydrogen: NVIDIA GeForce GT 1030 (2.0 GB via NVX_gpu_memory_info) | 2560x1440 @ 144Hz
Hydrogen: baseline p50 7.72ms p95 19.07ms jitter 7.46ms churn 569MB/s headroom 0.36x
Hydrogen: tuned to target 13.89ms stall 18.75ms | drs 0.70-1.00 step 0.040 | vram evict 85%
```

Headroom of 0.36 means 144Hz was never happening, so it retargeted to 72 and let
resolution scaling do the rest.

### Works with what you already run

Sodium, Iris and C2ME all work fine. With Sodium installed, Hydrogen's vanilla
chunk ordering switches itself off because Sodium has its own scheduler, and
everything else carries on. With VulkanMod, resolution scaling switches off
because there's no GL framebuffer to redirect, and the CPU and GC features keep
working. Hydrogen never replaces anyone's renderer or shaders. It reads metrics
around them.

### Configuration

`config/hydrogen.properties`. Almost every value says `auto`. Replace one with a
number and that value is pinned while everything else keeps tuning itself.

```properties
drs.minScale=0.70                       # never scale the world below this
target.frameTimeMs=auto                 # set 16.7 to simply target 60fps
cpu.governor.allowPowerPlanSwitch=auto  # auto = AC power only
log.verbose=false                       # log every tuning decision
```

### Honest limits

Resolution scaling on 1.21.9+ and 26.x uses the new Blaze3D API, which is still
changing between snapshots. It's implemented but opt-in behind
`drs.allowNewBlaze3d=true`. On 1.20.x and 1.21.1 it's on by default.

Chunk deferral isn't available on 26.x, because that version reworked how
sections get invalidated. Cone prioritisation still works there.

Resolution scaling doesn't mix with Fabulous graphics. Leave it off if you use
Fabulous.

There's no settings GUI yet, only the config file.

If native thread pinning is denied, or the power governor isn't writable, or your
driver reports no VRAM extension, the affected feature degrades quietly and logs
once. Nothing crashes and nothing spams your console.

### Requirements

Fabric Loader and Fabric API. Client side only, so you don't need it on a server.
Pick the jar matching your Minecraft version.

MIT licensed. Source and issues on GitHub.

---

## 3. Changelog for version 1.0.0

```
First release.

Adds:
- Topology-aware core binding. Reads real P-core, E-core and SMT layout from the
  OS and keeps chunk meshing off the render thread's cores.
- Frame-driven clock boosting tied to your monitor's actual refresh rate, with a
  fallback that lifts clocks without root on Linux.
- GC scheduling into idle moments, with combat lockout and heap triggers derived
  from your measured allocation rate.
- Decoupled dynamic resolution. The 3D world scales, the HUD stays native, and
  your configured floor is never crossed.
- VRAM eviction with thresholds that follow your card's actual size, reported by
  the driver rather than assumed.
- Sub-pixel entity culling that follows your DPI scaling and live viewport.
- Motion-vector chunk prioritisation into a 60 degree forward cone.
- Sound pool priority tiers with distance culling, so gameplay audio survives a
  saturated 247-channel pool.
- Sub-pixel culling for block entity renderers.
- Particle physics culling behind the camera. Particles still age and expire
  normally.
- Optional distance-based passive mob AI thinning (off by default).
- Optional idle hopper scan thinning (off by default).
- Five second calibration pass on world join that derives every threshold from
  measured frame times, jitter and allocation churn.
- Refresh-divisor retargeting: if your machine cannot reach your monitor's rate,
  the target steps down to one it can hold instead of chasing it forever.

Compatible with Sodium, Iris, VulkanMod and C2ME. Conflicting hooks disable
themselves automatically.

Known limits: resolution scaling is opt-in on 1.21.9+ and 26.x while the new
Blaze3D API settles. Chunk deferral is unavailable on 26.x. No settings GUI yet.
```
