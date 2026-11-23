# Sharding

Reef can split one world across several servers behind a [Velocity](https://papermc.io/software/velocity) proxy. Each server ("shard") owns part of the map. When a player walks, rides or teleports across a border, Reef saves them and asks Velocity to move them to the shard that owns that area. They arrive at the same spot with the same inventory, ender chest, health, effects, XP and the boat or horse they were riding.

Use it when one server can't keep up with the number of players. Below roughly 100–200 players, one server is simpler and faster.

> **Status: phase 1 (experimental).** Moving between shards works. Plugin data, chat and the tab list are not shared yet (see [Limitations](#limitations)).

## How it works

- Every shard has a **full, identical copy of the world** but only simulates its own regions.
- Outside its own regions a shard is read-only: chunks there don't tick, mobs there are frozen, and players can't change blocks.
- Players also can't change blocks within `border-protection` blocks of a border, so builds, water and lava never spill into another shard's area.
- All shards read and write **player data in one shared folder**. When a player crosses a border, the shard saves them, waits for the write to finish, then sends Velocity a `Connect` message over the BungeeCord plugin channel.
- If the other shard is offline, the player is sent back from the border after `handoff-timeout-seconds`.
- When a player logs in to any shard and their saved position belongs to another shard, they're moved there right away. You can point Velocity at any shard.

## Setup

1. **Velocity:** add every shard to `[servers]` in `velocity.toml` and keep `bungee-plugin-message-channel = true` (the default). Use modern forwarding as usual.
2. **World:** copy the **whole** world folder to every shard, **including `uid.dat`**. Every copy must have the same world ID, or players will be sent to spawn when they arrive.
3. **Shared player data:** create one folder that every shard can reach. That's either a folder on the same machine, or a network share if the shards run on different machines. When a shard starts, it copies any player files from its own `world/playerdata` into the shared folder that aren't there yet. It never overwrites one, so the shared copy always wins. A server that was split into shards keeps everyone's inventory and position without copying anything by hand.
4. **reef.yml** on every shard: the `regions` list must be **identical everywhere**. Only `this-server` differs.

```yaml
sharding:
  enabled: true
  this-server: europe            # this shard's name in velocity.toml
  world: world
  shared-player-data-folder: /srv/reef-shared/playerdata
  border-protection: 16
  handoff-timeout-seconds: 5
  regions:
    - {server: americas, min-x: -40000, min-z: -20000, max-x: -8000, max-z: 20000}
    - {server: europe,   min-x: -8000,  min-z: -20000, max-x: 8000,  max-z: 20000}
    - {server: asia,     min-x: 8000,   min-z: -20000, max-x: 40000, max-z: 20000}
```

- A server can own several areas, in block coordinates. Each area is one of:
  - **Rectangle:** `{server: a, min-x: .., min-z: .., max-x: .., max-z: ..}` (min inclusive, max exclusive).
  - **Polygon:** `{server: a, points: [[x, z], [x, z], [x, z], ...]}`, at least 3 points. Add `smooth: true` for a curve through the points.
  - **Circle or ellipse:** `{server: a, center-x: .., center-z: .., radius: ..}`, or `radius-x` and `radius-z` for an ellipse.
- The map editor in GinjerMap can draw these shapes and copy the `regions` list for you.
- If areas overlap, the first one in the list wins.
- Keep region edges on multiples of 16 where you can. Chunks belong to the server whose area contains their centre, so curved borders follow the shape to within a chunk.
- Any position outside every region is treated as the edge of the world, and players are pushed back.
- Changing `shared-player-data-folder` needs a restart. Everything else applies with `/reef reload`.

`/reef` shows whether sharding is on, which server this is, and how many handoffs are in progress.

## For plugin developers

Plugins can ask which shard simulates a position, without touching server internals (call them by reflection if your plugin also runs on plain Paper):

- `dev.reef.sharding.Sharding.ownsBlock(String world, double x, double z)`: true if this shard simulates that spot (also true for worlds that are not sharded, and when sharding is off).
- `dev.reef.sharding.Sharding.ownerAt(String world, double x, double z)`: the owning shard's name, or null.

Use it to run block-bound features (machines, timed spawns) only on the shard that owns the block.

## Limitations

- **Plugin data isn't shared.** Plugins that keep data per server (economy, claims, towns, homes) need their own database-backed cross-server support. LuckPerms with MySQL works, for example.
- **Chat and the tab list are per shard.** Use a proxy chat plugin for global chat.
- **Statistics and advancements are per shard.** They live in each world folder.
- **Players can't see players or mobs on the other side of a border.** The terrain looks the same because every shard has the same map, but anything the other shard changed after the copy isn't visible. Mobs near a border on your side freeze once they wander across.
- **Only one world is sharded.** The Nether and End should exist on a single shard, or be disabled.
- **Teleport commands across regions** work, because the player gets handed over at the destination. Plugin-based warps that check location first may not.
