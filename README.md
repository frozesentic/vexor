# Vexor

**Vexor** is a Fabric server-side mod for Minecraft 1.21.11 that silently tracks all player positions and generates nocom-style thermal heatmaps showing exactly where players have been.

Inspired by the [nocom exploit on 2b2t](https://www.youtube.com/watch?v=elqAh3GWRpA) — but implemented as a legitimate server mod with no client required.

<img width="2048" height="2048" alt="vexor_overworld_full_1780769375355" src="https://github.com/user-attachments/assets/2a18e40a-5960-448c-8c7e-e1e967225443" />

---

## Features

- **Silent position tracking** — samples every player's location every second with no client-side mod needed
- **Path interpolation** — fills in movement between samples using Bresenham line drawing, so paths appear as continuous trails rather than dots
- **Thermal heatmap generation** — exports high-resolution PNG images with a nocom-style colormap (black → purple → magenta → red → orange → white)
- **Auto-bounds mode** — `/vexor heatmap` with no arguments automatically fits the entire map to all tracked data, no matter how far players have travelled
- **Owner-only access** — all commands require op level 4; invisible to normal players and lower-level ops
- **Persistent data** — tracking data survives server restarts; auto-saves every 5 minutes
- **Live minimap HUD** — client-side overlay showing your own heat trail, online players, compass, and zoom levels
- **CSV export** — dump all raw tracking data for external analysis (QGIS, Python, etc.)

---

## Installation

1. Download the mod JAR from the [releases page](https://github.com/frozesentic/vexor/releases)
2. Drop it into your server's `mods/` folder — **no client mod required**
3. Install [Fabric Loader](https://fabricmc.net/use/server/) and [Fabric API](https://modrinth.com/mod/fabric-api) if you haven't already
4. Start the server — tracking begins automatically on first player join

Heatmap images are saved to `<server-root>/vexor/heatmaps/`.
Raw data is stored at `<server-root>/vexor/vexor_data.json`.

---

## Commands

All commands require **op level 4** (server owner).

| Command | Description |
|---|---|
| `/vexor heatmap` | Generate a full-map PNG auto-fitted to all tracked data |
| `/vexor heatmap <radius>` | Generate centered on your position with the given block radius |
| `/vexor heatmap <x> <z> <radius>` | Generate centered on specific world coordinates |
| `/vexor stats` | Overall stats: tracked cells, players, sample count, dimensions |
| `/vexor stats <player>` | Per-player stats: sessions, total playtime, most visited dimension |
| `/vexor top [count]` | Top N most-visited locations with block coordinates |
| `/vexor path <player> [count]` | Recent timestamped position history for any player |
| `/vexor session` | All online players with live coordinates, dimension, and total playtime |
| `/vexor track on\|off` | Enable or disable position tracking |
| `/vexor clear [dimension]` | Wipe all tracking data (or just one dimension) |
| `/vexor save` | Force-write data to disk immediately |
| `/vexor reload` | Re-read data from disk |
| `/vexor export` | Export all cell data as a CSV file |
| `/vexor help` | List all commands |

---

## Heatmap Output

Generated images are 2048×2048 PNG (auto-bounds) or 1024×1024 (fixed-radius).

**Colormap** (lowest → highest intensity):

```
■ Black      — unvisited
■ Deep purple — rarely visited (1–2 passes)
■ Magenta    — occasional traffic
■ Red-orange — moderate activity
■ Orange     — busy area
■ White      — extreme hotspot (spawn, highway intersections)
```

Intensity uses a **logarithmic scale** so even single-visit paths show up as dim purple — exactly like the nocom heatmap. Frequently travelled highways and roads appear as thin bright lines.

**Tracking resolution:** 4×4 block cells with Bresenham path interpolation between 1-second samples. Players walking along a road leave a continuous, single-cell-wide trail at ~4-block precision.

---

## Client Minimap (optional)

When you install the mod on a client, a minimap HUD appears in the top-right corner showing your own heat trail.

| Key | Action |
|---|---|
| `H` | Toggle minimap on/off |
| `Numpad +` | Zoom in |
| `Numpad -` | Zoom out |

Zoom levels: 64 / 128 / **256** / 512 / 1024 blocks across (default 256).

---

## Privacy & Ethics

This mod is intended for **server owners** to understand how their own players use their own world — for base detection, anti-grief investigation, spawn placement, or analytics.

- It requires **op level 4** so only the server owner can access data
- Normal players and lower-level ops see no indication it is running
- Use responsibly and in accordance with your server's rules

---

## Building from Source

```bash
git clone https://github.com/frozesentic/vexor.git
cd vexor
./gradlew build
# JAR is in build/libs/
```

Requires Java 21 and Gradle (wrapper included).

| Dependency | Version |
|---|---|
| Minecraft | 1.21.11 |
| Fabric Loader | ≥ 0.19.3 |
| Fabric API | 0.141.3+1.21.11 |
| Java | 21 |

---

## Data Format

`vexor/vexor_data.json` stores heat cells as packed 64-bit keys (cellX in high 32 bits, cellZ in low 32 bits) mapped to visit counts, grouped by dimension identifier:

```json
{
  "heat": {
    "minecraft:overworld": {
      "4294967296": 42,
      ...
    }
  },
  "players": {
    "PlayerName": {
      "sessions": 12,
      "playTimeMs": 3600000,
      "totalSamples": 3600,
      "mostVisitedDimension": "minecraft:overworld"
    }
  }
}
```

CSV export (`/vexor export`) includes columns: `dimension, chunkX, chunkZ, blockX, blockZ, visits`.

---

## License

MIT
