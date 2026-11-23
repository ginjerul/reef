# Reef

A performance-focused fork of [Paper](https://github.com/PaperMC/Paper) for Minecraft **1.21.11**, porting optimizations from [Pufferfish](https://github.com/pufferfish-gg/Pufferfish).

Reef aims to stay fully compatible with Paper plugins. Optimizations that change vanilla behaviour are toggleable in `reef.yml`.

## Features

| Patch | Origin | Config (`reef.yml`) | Default |
|---|---|---|---|
| Optimize suffocation checks | Pufferfish | `optimizations.check-stuck-in-wall-interval` | `10` (vanilla = `1`) |
| Dynamic Activation of Brain (DAB) | Pufferfish / Airplane | `optimizations.dynamic-activation-of-brain` | on, villagers & a few others blacklisted |
| Async mob spawn counting | Pufferfish | `optimizations.async-mob-spawning` | `true` |
| Slower idle animal AI checks (tempt / follow parent) | Reef (idea from Leaf) | `optimizations.goal-check-interval` | `10` / `20` ticks (vanilla = `2`) |
| Only players push mobs | Leaf | `optimizations.only-players-push-entities` | `false` |
| Async player data saving | Reef (idea from Nitori / Leaf) | `optimizations.async-player-data-saving` | `true` |
| Skip useless entity move packets | Reef (idea from Purpur) | `optimizations.skip-useless-move-packets` | `true` |
| Faster collision candidate filter | Leaf | – | always on |

## Lag tools

Built in, configured in the `lag` section of `reef.yml`:

- **Dynamic view distance** (on by default). When the average tick takes longer than 45 ms, Reef lowers the simulation distance a step, then the view distance, down to 3 and 5 chunks. Once ticks stay under 35 ms for a minute, it raises them again, view first, up to `view-distance` and `simulation-distance` in `server.properties`. `/reef` shows the current values.
- **Entity clearer.** It removes dropped items, arrows stuck in blocks and experience orbs. Mobs, named items and thrown tridents are never removed.
  - `/reef clear <items|arrows|xp|all> [seconds]` clears now, after a warning in chat when seconds are given.
  - Scheduled clearing is off by default.
  - Per-chunk limits are on by default: 300 items, 100 arrows and 100 orbs. The oldest beyond the limit are removed every 30 seconds, so a broken farm can't build a pile of thousands.
- **Entity report.** `/reef entities` counts the entities and block entities (hoppers, furnaces, ...) in every loaded chunk. It lists the worst chunks and writes the top 30 to `reef-entities.json`, which the Ginjer Panel's lag hotspots page reads.

On a shard, the clearer only touches the chunks that shard owns.

## Sharding (experimental)

Reef can split one world across several servers behind Velocity. Players are handed to the right server when they cross a border. See [SHARDING.md](SHARDING.md) for setup and limitations. It's off by default.

## Commands

- `/reef` – show which optimizations are on and their current values
- `/reef reload` – re-read `reef.yml` without restarting; every setting applies right away
- `/reef clear <items|arrows|xp|all> [seconds]` – clear ground items, stuck arrows and experience orbs, after a warning
- `/reef entities` – find the chunks with the most entities and block entities

Both need operator or the `reef.command` permission.

## Update checks

At startup and in `/version`, Reef compares the running build with the latest commit on GitHub (`update-checker` in `reef.yml`, default `ginjerul/reef` / `main`). This only works while the repository is public.

## Building

Requirements: **Java 21** (e.g. [Temurin 21](https://adoptium.net/)) and **git**.

First time only (the build needs the project to be a git repository):

```bash
chmod +x gradlew
git init && git add . && git commit -m "Initial Reef setup"
```

Then:

```bash
./gradlew applyAllPatches
./gradlew createMojmapPaperclipJar
```

The server jar is the `*-paperclip-*-mojmap.jar` file in `reef-server/build/libs/`. Upload it to your server in place of the Paper jar.

Pushing to GitHub also builds the jar automatically. Download it from the **Actions** tab (artifact `reef-1.21.11`). Every push to `main` also publishes the jar as the `dev-main` pre-release, so the newest build always has the same link, no login needed:

```
https://github.com/ginjerul/reef/releases/download/dev-main/reef.jar
```

## Development

Reef uses [paperweight](https://github.com/PaperMC/paperweight) patches on top of Paper.

- `reef-server/minecraft-patches/features/` – patches to Minecraft source (most optimizations go here)
- `reef-server/paper-patches/features/` – patches to Paper/CraftBukkit source
- `reef-api/paper-patches/` – patches to the Paper API
- `reef-server/src/main/java/dev/reef/` – Reef's own classes (e.g. `ReefConfig`)

Typical workflow:

1. `./gradlew applyAllPatches`
2. Edit code in `reef-server/src/minecraft/java` and commit there (one commit per feature)
3. `./gradlew rebuildAllServerPatches` to turn the commits back into patch files

Mark every change with `// Reef start - <feature>` / `// Reef end - <feature>` comments so rebasing onto newer Paper builds stays easy.

**Base:** Paper `ver/1.21.11` @ `c5eb0790f199da6c38d0a650e1e5cd5415b28185` (set in `gradle.properties` as `paperRef`).

## License & credits

Reef is licensed under the [GNU General Public License v3.0](LICENSE), inherited from Paper, Spigot, CraftBukkit and Bukkit.

- [PaperMC](https://github.com/PaperMC/Paper) – upstream server and fork template
- [Pufferfish](https://github.com/pufferfish-gg/Pufferfish) by Kevin Raneri and contributors – original optimization patches
- [Gale](https://github.com/Dreeam-qwq/Gale) and [Leaf](https://github.com/Winds-Studio/Leaf) – reference ports of Pufferfish patches to newer versions

Reef is not affiliated with PaperMC, Pufferfish or Pufferfish Host.
