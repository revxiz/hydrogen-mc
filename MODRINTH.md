# Modrinth listing copy

Everything below is ready to paste. Section 1 is the project settings, section 2
is the page body, section 3 is the changelog for the 1.2.0 upload.

---

## 1. Project settings

**Name**

```
Hydrogen
```

**Summary** (Modrinth caps this at 256 characters)

```
Performance mod with no hardcoded numbers. It measures your CPU, GPU, monitor and frame times at runtime, then tunes core placement, clock boosting, GC timing, VRAM eviction and dynamic resolution for your machine. Fabric, Quilt, Forge, NeoForge.
```

**Categories:** Optimization, Utility

**Loaders:** Fabric, Quilt, Forge, NeoForge

**Client/server:** Client required, server optional (two simulation features work server side)

**Licence:** MIT

**Links**

- Source: `https://github.com/revxiz/hydrogen-mc`
- Issues: `https://github.com/revxiz/hydrogen-mc/issues`

**Suggested tags for the search blurb:** dynamic resolution, FPS, low end,
thread affinity, VRAM, Sodium compatible

**Files to upload** (one version entry per file, tagged as shown)

| File | Loaders | Game versions |
|---|---|---|
| `hydrogen-fabric-1.20.1-1.2.0.jar` | Fabric | 1.20 to 1.20.4 |
| `hydrogen-quilt-1.20.1-1.2.0.jar` | Quilt | 1.20 to 1.20.4 |
| `hydrogen-forge-1.20.1-1.2.0.jar` | Forge, NeoForge | 1.20.1 |
| `hydrogen-fabric-1.21.1-1.2.0.jar` | Fabric | 1.21, 1.21.1 |
| `hydrogen-quilt-1.21.1-1.2.0.jar` | Quilt | 1.21, 1.21.1 |
| `hydrogen-forge-1.21.1-1.2.0.jar` | Forge | 1.21.1 |
| `hydrogen-neoforge-1.21.1-1.2.0.jar` | NeoForge | 1.21.1 |
| `hydrogen-<loader>-1.21.11-1.2.0.jar` | one each | 1.21.11 |
| `hydrogen-fabric-26.1-1.2.0.jar` | Fabric | 26.1, 26.1.1, 26.1.2 |
| `hydrogen-quilt-26.1-1.2.0.jar` | Quilt | 26.1, 26.1.1, 26.1.2 |
| `hydrogen-forge-26.1-1.2.0.jar` | Forge | 26.1.2 |
| `hydrogen-neoforge-26.1-1.2.0.jar` | NeoForge | 26.1.2 |
| `hydrogen-<loader>-26.2-1.2.0.jar` | one each | 26.2 |
| `hydrogen-<loader>-26.3-1.2.0.jar` | one each | 26.3 |

---

## 2. Page body

Most performance mods ship numbers somebody picked on their own PC. Cap frame
time at 16 ms. Evict textures at 2 GB. Cull past 64 blocks. Those numbers are
right for one machine, and it probably isn't yours.

Hydrogen ships none of them.

It asks Windows, Linux or macOS to describe your CPU. It asks your graphics
driver how much VRAM actually exists. It asks the window system (GLFW, or SDL3
on 26.3) what your monitor runs at and what your DPI scaling is. Then it watches
your game for a few seconds and works out every threshold from what it measured.
A 4K 60 Hz desktop and a 1080p 240 Hz laptop end up with different settings and
nobody opens a config file.

It needs nothing else installed: no Fabric API, no Quilt Standard Libraries, no
config library.

### What it does

**Keeps the render thread's cores to itself.** Hydrogen reads your real CPU
layout, including P and E cores, hyperthread siblings, cache sizes and the
firmware's ranking of its best cores. The render thread gets the best cores in
one cache domain (the V-Cache CCD on Ryzen X3D parts), and the game's worker
threads, the integrated server and other mods' threads are moved off them. A
chunk build never lands on the core drawing your frame.

**Asks for clocks when frames get tight.** Your monitor sets the target: 144 Hz
means 6.94 ms. When frames overrun it, Hydrogen switches Windows to High
Performance, or writes `performance` to the Linux cpufreq governor, and puts it
back when things settle. On battery it leaves you alone, and if the game crashes
while boosted, the next launch restores your old plan. If your machine can't
reach your panel's refresh rate, it aims at a sensible divisor instead.

**Moves garbage collection out of your way.** Collections are requested in
moments you won't feel them, from a background thread so the frame never waits:
game paused, inventory open, or standing still when your collector runs
concurrently. Swung a sword in the last six seconds? It waits.

**Scales the world, not the HUD.** When the GPU falls behind or VRAM fills, the
3D world renders smaller and gets scaled back up. Your HUD, text, crosshair and
menus draw afterwards at full resolution and stay sharp. You set the floor:
`drs.minScale=0.70` never goes below 70%.

**Clears VRAM before the driver panics.** Free and total memory come straight
from the driver, and thresholds follow the card. Only textures nobody has drawn
for a while are released; atlases and player skins are never touched. If no
driver extension reports memory, the feature switches itself off instead of
guessing.

**Stops drawing things smaller than a pixel**, with the threshold following your
DPI scaling. **Builds chunks where you're looking** first. **Protects your
sound pool** so gameplay sounds survive when all 247 channels are busy. **Skips
collision for particles behind you** while they keep moving and ageing.

**Optionally thins distant simulation.** Two switches, both off by default:
passive mobs beyond 48 blocks can run AI one tick in four (villagers and traders
excluded), and empty hoppers can thin their search for dropped items without
changing hopper timing.

### Watch your game from a browser

Press **Alt + H** in a world and Hydrogen shows a one-time code in chat. Type it
into the console page on your phone or a second screen and you get a live view
of the game: the slowest frames against your screen's target, frames per
second, memory, world resolution and server tick time, plus warnings and errors
from the game log as they happen. Every Hydrogen feature has a switch, so you
can turn one off mid-game and see what it was doing for you. On a dedicated
server, type `hydrogen console on` into the server console instead.

The console is off until you turn it on, and the first press explains what it
sends before anything leaves your PC.

**What it sends while on:** frame times, FPS, memory, world resolution and tick
time; Minecraft, loader, Java and Hydrogen versions; your OS, CPU and GPU names,
screen size and refresh rate; the mod list; and warnings and errors from the
game log with home folder paths, IP addresses, email addresses, UUIDs and your
player name removed first. `console.gameLog=false` limits the log to Hydrogen's
own lines. It never reads chat, your position, world or server names, your
account, files or screenshots, though a warning that a mod wrote with a world
or server name in it goes out as written.

**What it keeps:** the last hour of numbers and the last 200 log lines.
Turning the console off from its page deletes everything on the server at once,
and a PC that stays silent for 30 days is deleted automatically.

**Why nobody else can get in:** codes are 12 characters from 32 symbols, last
10 minutes, work once, and each network gets 10 tries every 15 minutes. The
server only stores a hash of the code. A linked browser can flip the listed
switches and press the listed buttons, and the game checks that list again
before acting, so nothing else in your game or config can be reached.

### Works with what you already run

Sodium, Embeddium, Iris, Oculus and C2ME all work. With Sodium installed,
Hydrogen's vanilla chunk ordering switches itself off because Sodium has its
own scheduler. With VulkanMod, resolution scaling switches off and the CPU and
GC features keep working. Hydrogen never replaces anyone's renderer or shaders.

### Configuration

`config/hydrogen.properties`. Almost every value says `auto`. Replace one with a
number and that value is pinned while everything else keeps tuning itself.

```properties
# Never scale the world below this
drs.minScale=0.70
# Set 16.7 to simply target 60 fps
target.frameTimeMs=auto
# auto = AC power only
cpu.governor.allowPowerPlanSwitch=auto
# Log every tuning decision
log.verbose=false
```

### Honest limits

Resolution scaling on 1.21.11 and 26.x is opt-in behind
`drs.allowNewBlaze3d=true`, because Mojang's newer render layer still changes
between releases. On 1.20.x and 1.21.1 it's on by default.

On 26.3's Vulkan backend the VRAM features stay off, because memory readings
come from OpenGL extensions.

There's no settings screen yet, only the config file and the console page.

If thread pinning is denied, or the power governor isn't writable, or your
driver reports no VRAM extension, the affected feature steps back quietly and
logs once. Nothing crashes and nothing spams your console.

MIT licensed. Source and issues on GitHub.

---

## 3. Changelog for version 1.2.0

```
New:
- Remote console. Press Alt+H in a world to get a one-time code, type it into the
  console page, and watch frame times, FPS, memory, world resolution, server tick
  time and the game's warnings live from any browser. Each Hydrogen feature gets
  a switch for the session, and there are buttons to collect garbage,
  recalibrate and reset world resolution. Off until you turn it on.
- Dedicated servers: "hydrogen console on" in the server console prints a code
  to the server log. Players can't run or see the command.
- Turning Hydrogen's master switch off mid-game now also hands back CPU clocks
  and resolution straight away, and keeps measuring so the difference shows.

Privacy:
- Nothing is sent unless you turn the console on. Log lines are scrubbed of home
  folders, IP and email addresses, UUIDs and your player name before they leave.
  Turning the console off from its page deletes this PC's data from the server.
```

## 4. Changelog for version 1.1.0

```
Now on Fabric, Quilt, Forge and NeoForge, for Minecraft 1.20 to 26.3.

New:
- Quilt, Forge and NeoForge builds next to Fabric.
- Minecraft 26.1.x and 26.3 branches. The old 26.x jar only really worked on 26.2.
- No dependencies. Fabric API is no longer needed.
- Worker threads, the integrated server and other mods' threads are now actually
  kept off the render cores. Before, only the render thread was pinned, and on
  Linux the threads it created inherited its cores.
- Cache-aware core choice: the render thread stays in one cache domain and
  prefers the V-Cache CCD on Ryzen X3D chips. Firmware core ranking is used.
- macOS gets quality of service classes in place of pinning.
- Windows opts the game out of EcoQoS power throttling.
- Crash recovery for the power plan and Linux governor.

Fixed:
- 1.20.1 jar crashed on 1.20.5 and 1.20.6, which it claimed to support. It now
  covers 1.20 to 1.20.4.
- 26.x jar crashed on 26.1.x and 26.3, which it claimed to support.
- Mesa and software renderers made VRAM eviction cut render distance on the
  title screen.
- The VRAM render distance trim was written to your options and never given back.
  It is now a session-only cap that recovers on its own.
- Texture eviction released every texture, including ones on screen and player
  skins, which then showed as missing. Only idle plain textures are released now.
- Resolution scaling lost parts of the world while glowing entities were visible.
- Lightning and scaled display entities could vanish to sub-pixel culling.
- Particles behind the camera froze in place; they now keep moving.
- The hopper throttle could change hopper clock timing and slow chests emptying
  into hoppers. It now only thins the dropped item search.
- A laptop unplugged while boosted stayed on High Performance until exit.
- Linux could leave CPU governors on performance after a partial failure.
- GC requests no longer block the render thread, and full stop-the-world
  collections are no longer started while you're in the world.
- Config lines with a comment after the value were silently ignored or read as
  false. Runtime self-disables are no longer saved into your config.
- Frame limit and vsync changes now update the frame target straight away.

Faster:
- Entity, particle, sound and chunk queue hooks read a snapshot instead of the
  config file on every call.
- Frame statistics take one sort per update instead of three.
- Power state is read every 10 seconds instead of 20 times a second.
```
