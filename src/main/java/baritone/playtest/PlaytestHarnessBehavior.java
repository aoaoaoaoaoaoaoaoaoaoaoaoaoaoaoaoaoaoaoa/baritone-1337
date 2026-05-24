package baritone.playtest;

import baritone.pathing.movement.MovementClientHelper;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.behavior.Behavior;
import baritone.playtest.PlaytestRun.TerminalReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;

public final class PlaytestHarnessBehavior extends Behavior {
  private static final String TRIAL_ENTITY_TAG = "baritone_playtest";
  private static final int TRIAL_ENTITY_CLEANUP_RADIUS = 192;
  private static final int TRIAL_ENTITY_CORRIDOR_CLEANUP_STRIDE = 64;
  private static final int TRIAL_ENTITY_CORRIDOR_CLEANUP_MAX_POINTS = 24;
  private static final int TRIAL_ENTITY_CORRIDOR_FORCELOAD_RADIUS = 24;
  private static final String ENABLED = "baritone.playtest.enabled";
  private static final String SCENARIO = "baritone.playtest.scenario";
  private static final String INBOX = "baritone.playtest.inbox";
  private static final String RESULTS = "baritone.playtest.results";
  private static final String SERVER = "baritone.playtest.server";
  private static final String QUIT = "baritone.playtest.quitOnFinish";
  private static final String CODE_STAMP = "baritone.playtest.codeStamp";
  private static final int CONNECT_RETRY_TICKS = 80;
  private static final int SETUP_CONVERGENCE_TICKS = 30;
  private static final int SETUP_TIMEOUT_TICKS = 20 * 45;
  private static final int INACTIVE_STOP_TICKS = 40;
  private static final int MOUNT_RETRY_TICKS = 20;

  private final Path inbox;
  private final Path results;
  private final String server;
  private final boolean quitOnFinish;
  private final Set<Path> consumed = new HashSet<>();
  private Pending pending;
  private PlaytestRun run;
  private Phase phase = Phase.IDLE;
  private int phaseTicks;
  private int inactiveTicks;
  private int commandIndex;
  private java.util.List<String> setupCommands = java.util.List.of();
  private boolean dead;
  private boolean connectInFlight;

  public PlaytestHarnessBehavior(Baritone baritone) {
    super(baritone);
    this.inbox = path(INBOX).orElse(null);
    this.results = path(RESULTS).orElse(baritone.getDirectory().resolve("playtest").resolve("results")).toAbsolutePath();
    this.server = System.getProperty(SERVER, "").trim();
    this.quitOnFinish = Boolean.getBoolean(QUIT);
    path(SCENARIO).ifPresent(path -> this.pending = new Pending(path, stem(path), false));
  }

  public static boolean enabled() {
    return Boolean.getBoolean(ENABLED) || System.getProperty(SCENARIO) != null || System.getProperty(INBOX) != null;
  }

  static String codeStamp() {
    return System.getProperty(CODE_STAMP, "unstamped");
  }

  @Override
  public void onTick(TickEvent event) {
    try {
      if (event.getType() == TickEvent.Type.OUT) {
        tickOut();
      } else {
        tickIn(event);
      }
    } catch (RuntimeException e) {
      if (run != null) {
        finish(TerminalReason.EXCEPTION, false);
      }
      throw e;
    }
  }

  @Override
  public void onPathEvent(PathEvent event) {
    if (run != null) {
      run.pathEvent(event);
    }
  }

  @Override
  public void onPlayerDeath() {
    dead = true;
  }

  @Override
  public void onWorldEvent(WorldEvent event) {
    if (run != null && phase == Phase.RUNNING && event.getWorld() == null) {
      finish(TerminalReason.DISCONNECT, false);
    }
  }

  private void tickOut() {
    if (pending == null) {
      pollInbox();
    }
    if (pending == null || server.isEmpty()) {
      return;
    }
    Minecraft minecraft = ctx.minecraft();
    if (minecraft.player != null) {
      connectInFlight = false;
      return;
    }
    if (connectInFlight && !(minecraft.screen instanceof DisconnectedScreen)) {
      return;
    }
    phaseTicks++;
    if (phaseTicks % CONNECT_RETRY_TICKS == 1) {
      connect(server);
    }
  }

  private void tickIn(TickEvent event) {
    if (pending == null && run == null) {
      pollInbox();
    }
    if (pending == null && run == null) {
      return;
    }
    Minecraft minecraft = ctx.minecraft();
    if (minecraft.player == null || minecraft.level == null) {
      return;
    }
    connectInFlight = false;
    switch (phase) {
      case IDLE -> startSetup(event);
      case SETUP -> tickSetup();
      case RUNNING -> tickRun(minecraft);
      case DONE -> {
      }
    }
  }

  private void pollInbox() {
    if (inbox == null || !Files.isDirectory(inbox)) {
      return;
    }
    try (var stream = Files.list(inbox)) {
      stream.filter(path -> path.getFileName().toString().endsWith(".json")).filter(path -> !consumed.contains(path.toAbsolutePath())).min(Comparator.comparing(path -> path.getFileName().toString()))
        .ifPresent(path -> {
          Path absolute = path.toAbsolutePath();
          consumed.add(absolute);
          pending = new Pending(absolute, stem(path), true);
          phase = Phase.IDLE;
          phaseTicks = 0;
          inactiveTicks = 0;
        });
    } catch (IOException e) {
      throw new RuntimeException("Unable to poll playtest inbox " + inbox, e);
    }
  }

  private void startSetup(TickEvent event) {
    Pending starting = pending;
    try {
      PlaytestScenario scenario = PlaytestScenario.load(starting.path(), starting.runId());
      run = new PlaytestRun(scenario, results, starting.path(), event.getCount());
      if (starting.deleteOnConsume()) {
        Files.deleteIfExists(starting.path());
      }
      pending = null;
      if (!scenario.harness().acceptsCodeStamp(codeStamp())) {
        finish(TerminalReason.STALE_CLIENT, false);
        return;
      }
      scenario.goldenTuning().installIfConfigured();
      dead = false;
      commandIndex = 0;
      phaseTicks = 0;
      inactiveTicks = 0;
      scenario.applySettings();
      setupCommands = setupCommands(scenario);
      phase = Phase.SETUP;
    } catch (IOException | RuntimeException e) {
      throw new RuntimeException("Unable to load playtest scenario " + (starting == null ? "<none>" : starting.path()), e);
    }
  }

  private void tickSetup() {
    phaseTicks++;
    LocalPlayer player = ctx.minecraft().player;
    if (player.isDeadOrDying() || player.getHealth() <= 0F) {
      player.respawn();
      phaseTicks = 0;
      return;
    }
    if (commandIndex < setupCommands.size()) {
      player.connection.sendCommand(bindScenarioCommand(setupCommands.get(commandIndex++), player));
      return;
    }
    if (phaseTicks > setupCommands.size() + SETUP_TIMEOUT_TICKS + runScenario().warmupTicks()) {
      finish(TerminalReason.SETUP_TIMEOUT, false);
      return;
    }
    retryRequiredMount(player);
    if (phaseTicks < setupCommands.size() + SETUP_CONVERGENCE_TICKS + runScenario().warmupTicks() || !readyToRun(player)) {
      return;
    }
    player.getInventory().setSelectedSlot(runScenario().selectedSlot());
    if (runScenario().trace()) {
      baritone.getMocapBehavior().start(run.tracePath());
    }
    switch (runScenario().action()) {
      case GOTO -> baritone.getCustomGoalProcess().setGoalAndPath(runScenario().baritoneGoal());
      case COMMANDS -> {
        if (!executeBaritoneCommands(runScenario().baritoneCommands())) {
          return;
        }
      }
    }
    phase = Phase.RUNNING;
    phaseTicks = 0;
    inactiveTicks = 0;
  }

  private void tickRun(Minecraft minecraft) {
    phaseTicks++;
    run.sample(baritone, minecraft);
    boolean active = baritone.getPathingBehavior().isPathing() || baritone.getPathingBehavior().getInProgress().isPresent() || baritone.getPathingBehavior().getPlanningStart().isPresent()
      || baritone.getBuilderProcess().isActive() || baritone.getPortalTaskProcess().isActive();
    inactiveTicks = active ? 0 : inactiveTicks + 1;
    if (dead) {
      finish(TerminalReason.DEATH, false);
      return;
    }
    Optional<TerminalReason> earlyFailure = runScenario().acceptance().earlyFailure(minecraft.player, run);
    if (earlyFailure.isPresent()) {
      finish(earlyFailure.get(), false);
      return;
    }
    if (runScenario().succeeded(minecraft.player, active, run)) {
      finish(TerminalReason.SUCCESS, true);
      return;
    }
    if (run.ticks() >= runScenario().timeoutTicks()) {
      finish(TerminalReason.TIMEOUT, false);
      return;
    }
    if (run.calcFailed()) {
      finish(TerminalReason.CALC_FAILED, false);
      return;
    }
    if (inactiveTicks > INACTIVE_STOP_TICKS) {
      finish(TerminalReason.PATH_STOPPED, false);
    }
  }

  private void finish(TerminalReason reason, boolean success) {
    Path telemetry = baritone.getMocapBehavior().output().orElse(null);
    if (baritone.getMocapBehavior().active()) {
      baritone.getMocapBehavior().stop();
    }
    boolean staleBefore = PlaytestRun.staleInputs(baritone);
    if (baritone.getBuilderProcess().isActive()) {
      baritone.getBuilderProcess().onLostControl();
    }
    baritone.getPathingBehavior().forceCancel();
    baritone.getInputOverrideHandler().clearAllKeys();
    boolean staleAfter = PlaytestRun.staleInputs(baritone);
    run.write(reason, success, baritone, ctx.minecraft(), telemetry, staleBefore, staleAfter);
    run = null;
    phase = quitOnFinish ? Phase.DONE : Phase.IDLE;
    phaseTicks = 0;
    if (quitOnFinish) {
      ctx.minecraft().stop();
    }
  }

  private boolean executeBaritoneCommands(java.util.List<String> commands) {
    for (String raw : commands) {
      String command = normalizeBaritoneCommand(raw);
      if (command.isBlank()) {
        continue;
      }
      if (!baritone.getCommandManager().execute(command)) {
        finish(TerminalReason.COMMAND_FAILED, false);
        return false;
      }
    }
    return true;
  }

  private static String bindScenarioCommand(String command, LocalPlayer player) {
    return command.replace("{player}", player.getScoreboardName());
  }

  private static String normalizeBaritoneCommand(String raw) {
    String command = raw.trim();
    while (command.startsWith("#")) {
      command = command.substring(1).stripLeading();
    }
    return command;
  }

  private PlaytestScenario runScenario() {
    return java.util.Objects.requireNonNull(run).scenario();
  }

  private java.util.List<String> setupCommands(PlaytestScenario scenario) {
    java.util.ArrayList<String> commands = new java.util.ArrayList<>();
    commands.add("gamerule advance_time false");
    commands.add("gamerule advance_weather false");
    commands.add("time set noon");
    commands.add("weather clear");
    commands.add("difficulty peaceful");
    commands.add("ride @s dismount");
    commands.add(String.format(Locale.ROOT, "execute at @s run kill @e[tag=%s,distance=..16]", TRIAL_ENTITY_TAG));
    commands.addAll(trialEntityCleanupCommands(scenario));
    commands.add("gamemode survival @s");
    commands.add("effect clear @s");
    commands.add("effect give @s minecraft:instant_health 1 10 true");
    if (scenario.saturationBoost()) {
      commands.add("effect give @s minecraft:saturation 1 10 true");
    }
    commands.add("clear @s");
    PlaytestScenario.Start start = scenario.start();
    int startX = (int) Math.floor(start.x());
    int startZ = (int) Math.floor(start.z());
    String forceLoadStart = String.format(Locale.ROOT, "execute in %s run forceload add %d %d %d %d", scenario.dimension(), startX - 16, startZ - 16, startX + 16, startZ + 16);
    String forceLoadStop = String.format(Locale.ROOT, "execute in %s run forceload remove %d %d %d %d", scenario.dimension(), startX - 16, startZ - 16, startX + 16, startZ + 16);
    commands.add(forceLoadStart);
    commands.add(String.format(Locale.ROOT, "execute in %s run tp @s %.3f %.3f %.3f %.2f %.2f", scenario.dimension(), start.x(), start.y(), start.z(), start.yaw(), start.pitch()));
    commands.addAll(scenario.setupCommands());
    commands.add(String.format(Locale.ROOT, "execute in %s run tp @s %.3f %.3f %.3f %.2f %.2f", scenario.dimension(), start.x(), start.y(), start.z(), start.yaw(), start.pitch()));
    commands.add(forceLoadStop);
    for (PlaytestScenario.LoadoutItem item : scenario.loadout()) {
      if (item.slot() >= 0) {
        commands.add(String.format(Locale.ROOT, "item replace entity @s hotbar.%d with %s %d", item.slot(), item.item(), item.count()));
      } else {
        commands.add(String.format(Locale.ROOT, "give @s %s %d", item.item(), item.count()));
      }
    }
    commands.addAll(scenario.postSetupCommands());
    return commands;
  }

  private static java.util.List<String> trialEntityCleanupCommands(PlaytestScenario scenario) {
    java.util.ArrayList<String> commands = new java.util.ArrayList<>();
    PlaytestScenario.Start start = scenario.start();
    commands.add(boundedTrialEntityKill(scenario.dimension(), start.x(), start.y(), start.z()));
    PlaytestScenario.GoalSpec goal = scenario.goal();
    double goalX = goal.x() + 0.5D;
    double goalZ = goal.z() + 0.5D;
    double dx = goalX - start.x();
    double dz = goalZ - start.z();
    if (dx * dx + dz * dz > TRIAL_ENTITY_CLEANUP_RADIUS * TRIAL_ENTITY_CLEANUP_RADIUS) {
      commands.add(boundedTrialEntityKill(scenario.dimension(), goalX, goal.y(), goalZ));
    }
    commands.addAll(corridorTrialEntityCleanupCommands(scenario, start, goalX, goal.y(), goalZ));
    return commands;
  }

  private static java.util.List<String> corridorTrialEntityCleanupCommands(PlaytestScenario scenario, PlaytestScenario.Start start, double goalX, double goalY, double goalZ) {
    double dx = goalX - start.x();
    double dz = goalZ - start.z();
    int intervals = (int) Math.ceil(Math.hypot(dx, dz) / TRIAL_ENTITY_CORRIDOR_CLEANUP_STRIDE);
    if (intervals <= 1 || intervals + 1 > TRIAL_ENTITY_CORRIDOR_CLEANUP_MAX_POINTS) {
      return java.util.List.of();
    }
    java.util.ArrayList<String> commands = new java.util.ArrayList<>((intervals + 1) * 3);
    for (int i = 0; i <= intervals; i++) {
      double t = i / (double) intervals;
      double x = start.x() + dx * t;
      double y = start.y() + (goalY - start.y()) * t;
      double z = start.z() + dz * t;
      commands.add(forceLoadTrialEntityCleanupTile(scenario.dimension(), x, z, true));
      commands.add(boundedTrialEntityKill(scenario.dimension(), x, y, z));
      commands.add(forceLoadTrialEntityCleanupTile(scenario.dimension(), x, z, false));
    }
    return commands;
  }

  private static String forceLoadTrialEntityCleanupTile(String dimension, double x, double z, boolean add) {
    int bx = (int) Math.floor(x);
    int bz = (int) Math.floor(z);
    int minX = bx - TRIAL_ENTITY_CORRIDOR_FORCELOAD_RADIUS;
    int minZ = bz - TRIAL_ENTITY_CORRIDOR_FORCELOAD_RADIUS;
    int maxX = bx + TRIAL_ENTITY_CORRIDOR_FORCELOAD_RADIUS;
    int maxZ = bz + TRIAL_ENTITY_CORRIDOR_FORCELOAD_RADIUS;
    return String.format(Locale.ROOT, "execute in %s run forceload %s %d %d %d %d", dimension, add ? "add" : "remove", minX, minZ, maxX, maxZ);
  }

  private static String boundedTrialEntityKill(String dimension, double x, double y, double z) {
    return String.format(Locale.ROOT, "execute in %s positioned %.3f %.3f %.3f run kill @e[tag=%s,distance=..%d]", dimension, x, y, z, TRIAL_ENTITY_TAG, TRIAL_ENTITY_CLEANUP_RADIUS);
  }

  private void retryRequiredMount(LocalPlayer player) {
    if (!runScenario().acceptance().requireHorseEncountered() || player.getVehicle() != null || phaseTicks % MOUNT_RETRY_TICKS != 0) {
      return;
    }
    player.connection.sendCommand("execute as " + player.getScoreboardName() + " at @s run ride @s mount @e[type=minecraft:horse,tag=" + TRIAL_ENTITY_TAG + ",limit=1,sort=nearest]");
  }

  private boolean readyToRun(LocalPlayer player) {
    if (runScenario().acceptance().requireHorseEncountered() && !(player.getVehicle() instanceof AbstractHorse)) {
      return false;
    }
    PlaytestScenario.Start start = runScenario().start();
    BlockPos pos = BlockPos.containing(start.x(), start.y(), start.z());
    Entity body = player.getVehicle() == null ? player : player.getVehicle();
    double dx = body.getX() - start.x();
    double dy = body.getY() - start.y();
    double dz = body.getZ() - start.z();
    boolean physicallySettled = body.onGround() || body.getDeltaMovement().lengthSqr() < 1.0E-5D || player.isInWater() || player.isUnderWater() || MovementClientHelper.isWater(ctx, pos)
      || MovementClientHelper.isWater(ctx, pos.below());
    return ctx.world().hasChunkAt(pos) && dx * dx + dy * dy + dz * dz <= 4D && physicallySettled && player.fallDistance <= 0.01F && player.getHealth() >= player.getMaxHealth() - 1.0E-4F;
  }

  private void connect(String address) {
    Minecraft minecraft = ctx.minecraft();
    ServerData data = new ServerData("Baritone Playtest", address, ServerData.Type.OTHER);
    ConnectScreen.startConnecting(minecraft.screen, minecraft, ServerAddress.parseString(address), data, false, null);
    connectInFlight = true;
  }

  private static Optional<Path> path(String property) {
    String raw = System.getProperty(property);
    return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(Path.of(raw));
  }

  private static String stem(Path path) {
    String name = path.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return dot < 0 ? name : name.substring(0, dot);
  }

  private record Pending(Path path, String runId, boolean deleteOnConsume) {
  }

  private enum Phase {
    IDLE, SETUP, RUNNING, DONE
  }
}
