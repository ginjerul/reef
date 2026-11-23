# Working in this repository

## Git
- Commit and push straight to `main`. The owner doesn't want pull requests or feature branches; they asked not to have to merge each change.
- Before pushing, make sure the change builds: pushing to `main` runs `.github/workflows/build.yml`, which builds the jar and publishes it as the `dev-main` release. Servers and the Ginjer Panel shard egg download that release, so a broken `main` reaches servers on their next update.

## The project
- Reef is a Paper fork built with paperweight. Minecraft source changes are patches in `reef-server/minecraft-patches/features/`; Reef's own classes are in `reef-server/src/main/java/dev/reef/`.
- Sharding is `dev/reef/sharding/Sharding.java` (see `SHARDING.md`). Nodes and GinjerMap call its `ownsBlock` and `ownerAt` by reflection, so keep those signatures.
- Related repositories: `ginjerul/ginjer-nodes` (Nodes and GinjerMap) and `ginjerul/ginjer-panel` (the Pelican plugin that manages sharded networks).
