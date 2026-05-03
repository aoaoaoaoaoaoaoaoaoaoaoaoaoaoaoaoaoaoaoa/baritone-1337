package baritone.config;

import baritone.api.BaritoneAPI;
import baritone.api.Settings;
import java.util.List;

public final class SettingsSurface {
  private SettingsSurface() {
  }

  public static List<Category> categories() {
    Settings s = BaritoneAPI.getSettings();
    return List.of(
      new Category("Movement",
        List.of(entry("Break blocks", s.allowBreak, "Allow paths through mineable blocks."), entry("Place blocks", s.allowPlace, "Allow bridge, pillar, and scaffold placements."),
          entry("Sprint", s.allowSprint, "Use sprinting movement costs and execution."), entry("Parkour", s.allowParkour, "Allow jump gaps; faster, less conservative."),
          entry("Parkour place", s.allowParkourPlace, "Allow jump-and-place gap crossing."), entry("Diagonal ascend", s.allowDiagonalAscend, "Allow safe diagonal ascents."),
          entry("Diagonal descend", s.allowDiagonalDescend, "Allow riskier diagonal descents."), entry("Oblique walking", s.allowObliqueWalk, "Enable conservative (2,1) walking edges."),
          entry("Mine downward", s.allowDownward, "Allow mining the block underfoot."), entry("Water-bucket falls", s.allowWaterBucketFall, "Allow long falls with bucket placement."),
          entry("No-water fall height", s.maxFallHeightNoWater, "Maximum normal fall distance."), entry("Bucket fall height", s.maxFallHeightBucket, "Maximum water-bucket fall distance."),
          entry("Walk on magma", s.allowWalkOnMagmaBlocks, "Sneak on magma instead of forbidding it."))),
      new Category("Pathing",
        List.of(entry("Chunk cache", s.chunkCaching, "Remember simplified chunk knowledge."), entry("Cached-only paths", s.pathThroughCachedOnly, "Do not query live world beyond cache."),
          entry("Cut at load boundary", s.cutoffAtLoadBoundary, "Trim paths to loaded chunks."), entry("Primary timeout ms", s.primaryTimeoutMS, "Normal A* success timeout."),
          entry("Failure timeout ms", s.failureTimeoutMS, "Absolute A* failure timeout."), entry("Plan-ahead timeout ms", s.planAheadPrimaryTimeoutMS, "Success timeout for next segment."),
          entry("Plan-ahead fail ms", s.planAheadFailureTimeoutMS, "Failure timeout for next segment."), entry("Movement timeout ticks", s.movementTimeoutTicks, "Executor timeout per movement."),
          entry("Path cutoff factor", s.pathCutoffFactor, "Static path suffix cutoff."), entry("Plan-ahead ticks", s.planningTickLookahead, "When to begin next-segment planning."))),
      new Category("Render",
        List.of(entry("Render path", s.renderPath, "Draw current and next path."), entry("Path as line", s.renderPathAsLine, "Draw line path instead of boxes."),
          entry("Render goal", s.renderGoal, "Draw active goal."), entry("Animated goal", s.renderGoalAnimated, "Animate the goal renderer."),
          entry("XZ beacon", s.renderGoalXZBeacon, "Render XZ goals as beacon beams."), entry("Render selection", s.renderSelection, "Draw selection boxes."),
          entry("Render cache", s.renderCachedChunks, "Draw cached chunks."), entry("Cache opacity", s.cachedChunksOpacity, "Opacity for cached chunk rendering."),
          entry("Fade path", s.fadePath, "Fade distant path segments."), entry("Path line width", s.pathRenderLineWidthPixels, "Path renderer line width."),
          entry("Goal line width", s.goalRenderLineWidthPixels, "Goal renderer line width."))),
      new Category("Control",
        List.of(entry("Prefix", s.prefix, "Command prefix."), entry("Prefix control", s.prefixControl, "Enable prefixed chat commands."),
          entry("Chat control", s.chatControl, "Enable unprefixed chat command recognition."), entry("Echo commands", s.echoCommands, "Echo commands back to chat."),
          entry("Short prefix", s.shortBaritonePrefix, "Use [B] instead of [Baritone]."), entry("Censor coordinates", s.censorCoordinates, "Hide coordinates in chat output."),
          entry("Censor commands", s.censorRanCommands, "Hide command arguments in echo."), entry("Toast logging", s.logAsToast, "Use toast popups for logs."),
          entry("Chat debug", s.chatDebug, "Emit debug messages to chat."))),
      new Category("Look",
        List.of(entry("Free look", s.freeLook, "Avoid forcing client-side rotations."), entry("Block free look", s.blockFreeLook, "Break/place without visible rotation."),
          entry("Smooth look", s.smoothLook, "Smooth server-side look packets."), entry("Smooth look ticks", s.smoothLookTicks, "Look smoothing window."),
          entry("Random yaw", s.randomLooking113, "Yaw randomization degrees."), entry("Random pitch/yaw", s.randomLooking, "Small pitch/yaw randomization."),
          entry("Anti-cheat mode", s.antiCheatCompatibility, "Prefer conservative execution behavior."))),
      new Category("Mining",
        List.of(entry("Legit mine", s.legitMine, "Mine only exposed/legitimately discovered ores."), entry("Legit mine Y", s.legitMineYLevel, "Strip-mining Y level."),
          entry("Explore for blocks", s.exploreForBlocks, "Explore when cache scan finds none."), entry("Only exposed ores", s.allowOnlyExposedOres, "Require visible ore exposure."),
          entry("Exposure distance", s.allowOnlyExposedOresDistance, "Exposure scan radius."), entry("Mine dropped items", s.mineScanDroppedItems, "Treat drops as mining targets."),
          entry("Ore target cap", s.mineMaxOreLocationsCount, "Maximum remembered ore targets."), entry("Cache scan count", s.maxCachedWorldScanCount, "Maximum cached world scan count."))),
      new Category("Builder",
        List.of(entry("Build in layers", s.buildInLayers, "Complete one layer before the next."), entry("Top-down layers", s.layerOrder, "Layer order: off bottom-up, on top-down."),
          entry("Layer height", s.layerHeight, "Layer height for builder."), entry("Start layer", s.startAtLayer, "Initial schematic layer."),
          entry("Skip failed layers", s.skipFailedLayers, "Continue after a failed layer."), entry("Only selection", s.buildOnlySelection, "Build only selected schematic region."),
          entry("Break from above", s.breakFromAbove, "Experimental builder mining mode."), entry("Map art mode", s.mapArtMode, "Only care about top block per column."),
          entry("Builder scan radius", s.builderTickScanRadius, "Per-tick builder rescan radius."))),
      new Category("Elytra",
        List.of(entry("Enabled", s.elytraEnabled, "Allow elytra pathing and automatic elytra promotion."), entry("Auto jump", s.elytraAutoJump, "Walk to ledges and launch automatically."),
          entry("Predict Nether terrain", s.elytraPredictTerrain, "Use Nether seed terrain prediction."), entry("Nether seed", s.elytraNetherSeed, "Seed for Nether terrain prediction."),
          entry("Firework policy", s.elytraFireworkPolicy, "RECOVERY or SPEED."), entry("Firework speed", s.elytraFireworkSpeed, "Minimum speed before SPEED-policy boosting."),
          entry("Auto swap elytra", s.elytraAutoSwap, "Swap damaged elytra automatically."), entry("Minimum durability", s.elytraMinimumDurability, "Swap/land durability threshold."),
          entry("Emergency land", s.elytraAllowEmergencyLand, "Land when durability/fireworks are low."), entry("Min fireworks", s.elytraMinFireworksBeforeLanding, "Firework safety reserve."),
          entry("Render simulation", s.elytraRenderSimulation, "Draw best simulated flight path."),
          entry("Debug overlay", s.elytraDebugOverlay, "Show live elytra controller state in the actionbar."))));
  }

  private static <T> Entry<T> entry(String title, Settings.Setting<T> setting, String description) {
    return new Entry<>(title, setting, description);
  }

  public record Category(String title, List<Entry<?>> entries) {
  }
  public record Entry<T>(String title, Settings.Setting<T> setting, String description) {
  }
}
