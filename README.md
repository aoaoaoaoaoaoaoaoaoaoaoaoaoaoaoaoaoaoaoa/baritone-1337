# baritone-1337

baritone-1337 is an optimized Fabric-only fork of [Baritone](https://github.com/cabaletta/baritone), the Minecraft pathfinding bot.

Compatibility: Minecraft `26.1.2`, Fabric Loader `0.19.2`, Java `26` toolchain with Java `25` bytecode.

Build:

```sh
./gradlew :fabric:build
```

Fabric artifacts are written to `dist/`. Use the standalone Fabric jar for normal playtesting.

## New Features

- Fabric-only Minecraft `26.1.2` port.
- Core pathfinding hardening and optimization.
- Unified next-path profiling via `#profile`.
- Conservative `(2, 1)` oblique walking primitives.
- Overworld elytra transport.
- Sprint-swimming path support.
- Better speculative/background pathing.
- Read-only Xaero waypoint routing via `#xwp`.

License: LGPL-3.0. Original project: https://github.com/cabaletta/baritone
