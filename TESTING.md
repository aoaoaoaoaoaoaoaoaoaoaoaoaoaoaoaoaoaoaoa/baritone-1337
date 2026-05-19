# Testing

## Live-Fire World Policy

`127.0.0.1:25565` is the persistent shared proving-ground server. This is not a disposable harness port. The terrain, command blocks, hand-built obstacles, and weird little skunkworks polygons are part of the test corpus.

Rules:

- No isolation by default. Automated live-fire tests should run on the same `smoke_vd10` world the user inspects.
- It is normal and desirable for `BaritoneTest` and the user to be connected simultaneously.
- Do not kill, restart, swap, or regenerate the base world while the user may be connected.
- Run automated playtests serially unless a test explicitly declares safe parallelism.
- Use bounded cleanup commands. A test may reset its own polygon, but must not indiscriminately purge the world.
- Clean up or avoid zombie Java/Xvfb processes; a finished playtest must not leave stray clients behind.
- Use alternate ports/roots only for explicitly destructive, quarantined, or generator-scale experiments.

## Standard World

The standard live-fire world is `smoke_vd10` on `127.0.0.1:25565`. Command blocks are enabled in playtest worlds.

The world baseline is peaceful, locked noon, with spawn/start pad at `20 114 0`.

## Testing Polygons

- Spawn/start pad: `20 114 0`, bedrock, peaceful, locked noon.
- Horse station: `1188 80 -1204`. Step on the pressure plate to purge stale trial horses, summon an average tamed saddled horse, and mount the nearest player.
- Horse course: `scenarios/playtest/horse_medium_course.json`, start `1200.5 80 -1200.5`, goal `1222 80 -1198`.
- Horse obsidian spiral/gap control: `scenarios/playtest/horse_obsidian_spiral_gap.json`, start `1196.5 80 -1162.5`, goal `1189 87 -1159`, requires zero jump ticks.
- Horse frontier hard climb: `scenarios/playtest/horse_frontier_hard_climb.json`, start `1173.5 118 -1109.5`, goal XZ `1191 -1057`, steep mountain climb with no planned jumps required.
- Straight swim transition: `scenarios/playtest/swim_transition_straight.json`, start `75.5 63 -195.5`, goal `73 63 -161`.
- River curve swim/boat: `scenarios/playtest/swim_river_curve.json` and `scenarios/playtest/boat_river_curve.json`, start `73.5 63 -160.5`, goal `59 63 -3`.
- Unloaded river macro swim/boat: `scenarios/playtest/macro_swim_unloaded_river.json` and `scenarios/playtest/macro_boat_unloaded_river_plan.json`, start `404.5 63 -6.5`, goal XZ `653 135`.
- Builder tunnel extrusion frame: around `929 129 -1051`; automated by `scenarios/playtest/builder_extrude_nether_frame.json`.
- Portal E2E chambers: `scenarios/playtest/nether_existing_portal_enter_e2e.json` at `4096.5 61 0.5`, and `scenarios/playtest/nether_build_portal_enter_e2e.json` at `0.5 61 0.5`.
- Macro dark-goal smoke: `scenarios/playtest/distant_dark_goal_plan.json`, start `20.5 114 0.5`, goal XZ `1020 0`.
- Stair run: `scenarios/playtest/stair_run.json`, start `100.5 114 0.5`, goal `107 121 0`.

The fuller local field atlas is `notes/testing-polygons.md`; it is ignored by git, but useful inside a long-lived worktree.
