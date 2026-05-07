package baritone.playtest;

import baritone.Baritone;
import baritone.api.event.events.PathEvent;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.behavior.Behavior;
import baritone.pathing.movement.MovementHelper;
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

public final class PlaytestHarnessBehavior extends Behavior {
  private static final String ENABLED = "baritone.playtest.enabled";
  private static final String SCENARIO = "baritone.playtest.scenario";
  private static final String INBOX = "baritone.playtest.inbox";
  private static final String RESULTS = "baritone.playtest.results";
  private static final String SERVER = "baritone.playtest.server";
  private static final String QUIT = "baritone.playtest.quitOnFinish";
  private static final int CONNECT_RETRY_TICKS = 80;
  private static final int SETUP_CONVERGENCE_TICKS = 30;
  private static final int SETUP_TIMEOUT_TICKS = 20 * 45;
  private static final int INACTIVE_STOP_TICKS = 40;

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
      player.connection.sendCommand(setupCommands.get(commandIndex++));
      return;
    }
    if (phaseTicks > setupCommands.size() + SETUP_TIMEOUT_TICKS) {
      finish(TerminalReason.SETUP_TIMEOUT, false);
      return;
    }
    if (phaseTicks < setupCommands.size() + SETUP_CONVERGENCE_TICKS || !readyToRun(player)) {
      return;
    }
    player.getInventory().setSelectedSlot(runScenario().selectedSlot());
    if (runScenario().trace()) {
      baritone.getMocapBehavior().start(run.tracePath());
    }
    baritone.getCustomGoalProcess().setGoalAndPath(runScenario().baritoneGoal());
    phase = Phase.RUNNING;
    phaseTicks = 0;
    inactiveTicks = 0;
  }

  private void tickRun(Minecraft minecraft) {
    phaseTicks++;
    run.sample(baritone, minecraft);
    boolean active = baritone.getPathingBehavior().isPathing() || baritone.getPathingBehavior().getInProgress().isPresent() || baritone.getPathingBehavior().getPlanningStart().isPresent();
    inactiveTicks = active ? 0 : inactiveTicks + 1;
    if (dead) {
      finish(TerminalReason.DEATH, false);
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
    commands.add("kill @e[type=!minecraft:player]");
    commands.add("gamemode survival @s");
    commands.add("effect clear @s");
    commands.add("effect give @s minecraft:instant_health 1 10 true");
    if (scenario.saturationBoost()) {
      commands.add("effect give @s minecraft:saturation 1 10 true");
    }
    commands.add("clear @s");
    commands.addAll(scenario.setupCommands());
    PlaytestScenario.Start start = scenario.start();
    commands.add(String.format(Locale.ROOT, "execute in %s run tp @s %.3f %.3f %.3f %.2f %.2f", scenario.dimension(), start.x(), start.y(), start.z(), start.yaw(), start.pitch()));
    for (PlaytestScenario.LoadoutItem item : scenario.loadout()) {
      if (item.slot() >= 0) {
        commands.add(String.format(Locale.ROOT, "item replace entity @s hotbar.%d with %s %d", item.slot(), item.item(), item.count()));
      } else {
        commands.add(String.format(Locale.ROOT, "give @s %s %d", item.item(), item.count()));
      }
    }
    return commands;
  }

  private boolean readyToRun(LocalPlayer player) {
    PlaytestScenario.Start start = runScenario().start();
    BlockPos pos = BlockPos.containing(start.x(), start.y(), start.z());
    double dx = player.getX() - start.x();
    double dy = player.getY() - start.y();
    double dz = player.getZ() - start.z();
    boolean physicallySettled = player.onGround() || player.isInWater() || player.isUnderWater() || MovementHelper.isWater(ctx, pos) || MovementHelper.isWater(ctx, pos.below());
    return ctx.world().hasChunkAt(pos) && dx * dx + dy * dy + dz * dz <= 4D && physicallySettled;
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
