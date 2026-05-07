package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.macro.core.MacroActionInstance;
import baritone.pathing.macro.core.MacroPlan;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.path.RouteExecutor;
import baritone.pathing.transport.TransportControl;
import baritone.pathing.transport.TransportSnapshot;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

public final class MocapBehavior extends Behavior {
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final int SOUNDING_RADIUS = 1;
  private static final int SOUNDING_SCAN_UP = 8;
  private static final int SOUNDING_SCAN_DOWN = 16;

  private BufferedWriter out;
  private Path output;
  private long startedNanos;
  private int samples;
  private String lastFailure;

  public MocapBehavior(Baritone baritone) {
    super(baritone);
  }

  public boolean active() {
    return out != null;
  }

  public Optional<Path> output() {
    return Optional.ofNullable(output);
  }

  public String start() {
    Path directory = baritone.getDirectory().resolve("profiles");
    Path file = directory.resolve("mocap-" + FILE_TIME.format(Instant.now()) + "-" + Long.toUnsignedString(System.nanoTime(), 36) + ".jsonl");
    return start(file);
  }

  public String start(Path file) {
    if (active()) {
      return "Mocap already active: " + output;
    }
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      output = file;
      out = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
      startedNanos = System.nanoTime();
      samples = 0;
      lastFailure = null;
      event("start");
      return "Mocap: " + output;
    } catch (IOException e) {
      lastFailure = e.toString();
      output = null;
      out = null;
      return "Failed to start mocap: " + lastFailure;
    }
  }

  public String stop() {
    if (!active()) {
      return lastFailure == null ? "Mocap idle" : "Mocap idle; last failure: " + lastFailure;
    }
    Path saved = output;
    event("stop", "samples", samples);
    close();
    return "Saved mocap: " + saved;
  }

  public String toggle() {
    return active() ? stop() : start();
  }

  public String status() {
    if (active()) {
      return "Mocap active: " + output + " (" + samples + " ticks)";
    }
    return lastFailure == null ? output == null ? "Mocap idle" : "Mocap idle; last output: " + output : "Mocap idle; last failure: " + lastFailure;
  }

  @Override
  public void onPostTick(TickEvent event) {
    if (event.getType() == TickEvent.Type.IN && active() && ctx.player() != null && ctx.world() != null) {
      tick(event);
    }
  }

  @Override
  public void onWorldEvent(WorldEvent event) {
    if (active()) {
      event("world_event", "state", event.getState());
      close();
    }
  }

  private void tick(TickEvent event) {
    try {
      LocalPlayer player = ctx.player();
      RouteExecutor executor = baritone.getPathingBehavior().getCurrent();
      TransportSnapshot transport = baritone.getPathingBehavior().transportSnapshot();
      TransportSnapshot.Plan currentPlan = transport.current().current();
      Rotation effective = ctx.playerRotations();
      StringBuilder json = base("tick");
      json.append(',');
      field(json, "tick", event.getCount()).append(',');
      field(json, "sample", ++samples).append(',');
      field(json, "dimension", ctx.world().dimension().identifier().toString()).append(',');
      field(json, "pos", player.position()).append(',');
      field(json, "feet", ctx.playerFeet()).append(',');
      field(json, "eyeY", player.getEyeY()).append(',');
      field(json, "velocity", player.getDeltaMovement()).append(',');
      field(json, "horizontalSpeed", horizontalSpeed(player.getDeltaMovement())).append(',');
      field(json, "yaw", player.getYRot()).append(',');
      field(json, "pitch", player.getXRot()).append(',');
      field(json, "headYaw", player.getYHeadRot()).append(',');
      field(json, "effectiveYaw", effective.getYaw()).append(',');
      field(json, "effectivePitch", effective.getPitch()).append(',');
      field(json, "onGround", player.onGround()).append(',');
      field(json, "inWater", player.isInWater()).append(',');
      field(json, "underWater", player.isUnderWater()).append(',');
      field(json, "eyeInWater", player.isEyeInFluid(net.minecraft.tags.FluidTags.WATER)).append(',');
      field(json, "swimming", player.isSwimming()).append(',');
      field(json, "fallFlying", player.isFallFlying()).append(',');
      field(json, "sprinting", player.isSprinting()).append(',');
      field(json, "crouching", player.isCrouching()).append(',');
      field(json, "pose", player.getPose()).append(',');
      field(json, "vehicle", vehicle(player.getVehicle())).append(',');
      field(json, "air", player.getAirSupply()).append(',');
      field(json, "maxAir", player.getMaxAirSupply()).append(',');
      field(json, "food", player.getFoodData().getFoodLevel()).append(',');
      field(json, "boatItems", boatItems(player)).append(',');
      field(json, "inputs", inputs()).append(',');
      field(json, "physicalInputs", physicalInputs()).append(',');
      field(json, "water", waterState(ctx.playerFeet())).append(',');
      field(json, "soundings", soundings(ctx.playerFeet())).append(',');
      field(json, "pathing", baritone.getPathingBehavior().isPathing()).append(',');
      field(json, "process", baritone.getPathingControlManager().mostRecentInControl().map(IBaritoneProcess::displayName).orElse(null)).append(',');
      field(json, "command", baritone.getPathingControlManager().mostRecentCommand().map(PathingCommand::toString).orElse(null)).append(',');
      field(json, "movementControl", transport.control()).append(',');
      field(json, "pathPosition", transport.current().position()).append(',');
      field(json, "pathLength", executor == null ? null : executor.size()).append(',');
      field(json, "movement", currentPlan == null ? null : currentPlan.movement()).append(',');
      field(json, "movementSrc", currentPlan == null ? null : currentPlan.src()).append(',');
      field(json, "movementDest", currentPlan == null ? null : currentPlan.dest()).append(',');
      field(json, "transport", transport(transport));
      json.append(',');
      field(json, "macroPlan", macroPlan(baritone.getPathingBehavior().getMacroPlan().orElse(null)));
      json.append("}\n");
      out.write(json.toString());
      out.flush();
    } catch (RuntimeException | IOException e) {
      lastFailure = e.toString();
      close();
    }
  }

  private void event(String type, Object... fields) {
    if (!active()) {
      return;
    }
    try {
      StringBuilder json = base(type);
      for (int i = 0; i + 1 < fields.length; i += 2) {
        json.append(',');
        field(json, String.valueOf(fields[i]), fields[i + 1]);
      }
      json.append("}\n");
      out.write(json.toString());
      out.flush();
    } catch (IOException e) {
      lastFailure = e.toString();
      close();
    }
  }

  private StringBuilder base(String type) {
    StringBuilder json = new StringBuilder(4096);
    json.append('{');
    field(json, "schema", 1).append(',');
    field(json, "scope", "mocap").append(',');
    field(json, "type", type).append(',');
    field(json, "elapsedNanos", System.nanoTime() - startedNanos);
    return json;
  }

  private Object transport(TransportSnapshot snapshot) {
    return (JsonWritable) json -> {
      TransportSnapshot.Executor current = snapshot.current();
      TransportSnapshot.Plan plan = current.current();
      json.append('{');
      field(json, "actual", snapshot.actual()).append(',');
      field(json, "planned", plan == null ? null : plan.mode()).append(',');
      field(json, "phase", plan == null ? null : plan.phase()).append(',');
      field(json, "terminal", plan == null ? null : plan.terminal()).append(',');
      field(json, "componentId", plan == null ? null : plan.componentId()).append(',');
      field(json, "plannedState", plan == null ? null : plan.plannedState()).append(',');
      field(json, "entry", plan == null ? null : plan.entry()).append(',');
      field(json, "progress", plan == null ? null : plan.progress()).append(',');
      field(json, "sequence", current.sequence()).append(',');
      field(json, "current", executor(current)).append(',');
      field(json, "next", executor(snapshot.next()));
      json.append('}');
    };
  }

  private Object macroPlan(MacroPlan plan) {
    if (plan == null) {
      return null;
    }
    return (JsonWritable) json -> {
      json.append('{');
      field(json, "src", plan.src()).append(',');
      field(json, "dest", plan.dest()).append(',');
      field(json, "localGoal", plan.localGoal()).append(',');
      field(json, "estimatedTicks", plan.totalVector().timeTicks()).append(',');
      field(json, "estimatedScore", plan.totalScore()).append(',');
      field(json, "cellBlocks", plan.cellBlocks()).append(',');
      field(json, "factualCells", plan.factualCells()).append(',');
      field(json, "unknownCells", plan.unknownCells()).append(',');
      field(json, "liveCells", plan.liveCells()).append(',');
      field(json, "cachedCells", plan.cachedCells()).append(',');
      field(json, "predictedCells", plan.predictedCells()).append(',');
      field(json, "priorCells", plan.priorCells()).append(',');
      field(json, "sequence", plan.sequence()).append(',');
      field(json, "firstUncertifiedAction", plan.firstUncertifiedAction()).append(',');
      field(json, "value", macroValue(plan)).append(',');
      field(json, "actions", macroActions(plan)).append(',');
      field(json, "vertices", macroVertices(plan)).append(',');
      field(json, "cellVertices", macroCellVertices(plan));
      json.append('}');
    };
  }

  private Object macroValue(MacroPlan plan) {
    return (JsonWritable) json -> {
      var value = plan.valueTelemetry();
      json.append('{');
      field(json, "planner", value.planner()).append(',');
      field(json, "expectedStartValue", value.expectedStartValue()).append(',');
      field(json, "floorStartValue", value.floorStartValue()).append(',');
      field(json, "expectedRepairPops", value.expectedRepairPops()).append(',');
      field(json, "floorRepairPops", value.floorRepairPops()).append(',');
      field(json, "expectedQueueSize", value.expectedQueueSize()).append(',');
      field(json, "floorQueueSize", value.floorQueueSize()).append(',');
      field(json, "target", value.target());
      json.append('}');
    };
  }

  private Object macroActions(MacroPlan plan) {
    return (JsonWritable) json -> {
      json.append('[');
      int end = Math.min(plan.actions().size(), 16);
      for (int i = 0; i < end; i++) {
        if (i != 0) {
          json.append(',');
        }
        MacroActionInstance action = plan.actions().get(i);
        json.append('{');
        field(json, "kind", action.kind()).append(',');
        field(json, "score", action.score()).append(',');
        field(json, "timeTicks", action.cost().timeTicks()).append(',');
        field(json, "before", action.before()).append(',');
        field(json, "after", action.after()).append(',');
        field(json, "transition", macroTransition(action));
        json.append('}');
      }
      json.append(']');
    };
  }

  private Object macroTransition(MacroActionInstance action) {
    if (action.surfaceTransition() == null) {
      return null;
    }
    return (JsonWritable) json -> {
      var transition = action.surfaceTransition();
      json.append('{');
      field(json, "mode", transition.mode()).append(',');
      field(json, "stage", transition.stage()).append(',');
      field(json, "componentId", transition.componentId()).append(',');
      field(json, "distance", transition.distance()).append(',');
      field(json, "dryStart", transition.dryStart()).append(',');
      field(json, "waterStart", transition.waterStart()).append(',');
      field(json, "waterEnd", transition.waterEnd()).append(',');
      field(json, "dryEnd", transition.dryEnd());
      json.append('}');
    };
  }

  private Object macroVertices(MacroPlan plan) {
    return (JsonWritable) json -> {
      json.append('[');
      int end = Math.min(plan.renderPositions().size(), 16);
      for (int i = 0; i < end; i++) {
        if (i != 0) {
          json.append(',');
        }
        blockPos(json, plan.renderPositions().get(i));
      }
      json.append(']');
    };
  }

  private Object macroCellVertices(MacroPlan plan) {
    return (JsonWritable) json -> {
      json.append('[');
      int end = Math.min(plan.vertices().size(), 32);
      for (int i = 0; i < end; i++) {
        if (i != 0) {
          json.append(',');
        }
        var vertex = plan.vertices().get(i);
        json.append('{');
        field(json, "pos", vertex.pos()).append(',');
        field(json, "evidence", vertex.evidence());
        json.append('}');
      }
      json.append(']');
    };
  }

  private Object executor(TransportSnapshot.Executor executor) {
    return (JsonWritable) json -> {
      json.append('{');
      field(json, "position", executor.position()).append(',');
      field(json, "size", executor.size()).append(',');
      field(json, "legIndex", executor.legIndex()).append(',');
      field(json, "done", executor.done()).append(',');
      field(json, "plan", plan(executor.current())).append(',');
      field(json, "sequence", executor.sequence());
      json.append('}');
    };
  }

  private Object plan(TransportSnapshot.Plan plan) {
    if (plan == null) {
      return null;
    }
    return (JsonWritable) json -> {
      json.append('{');
      field(json, "mode", plan.mode()).append(',');
      field(json, "movement", plan.movement()).append(',');
      field(json, "src", plan.src()).append(',');
      field(json, "dest", plan.dest()).append(',');
      field(json, "phase", plan.phase()).append(',');
      field(json, "terminal", plan.terminal()).append(',');
      field(json, "componentId", plan.componentId()).append(',');
      field(json, "plannedState", plan.plannedState()).append(',');
      field(json, "entry", plan.entry()).append(',');
      field(json, "progress", plan.progress()).append(',');
      field(json, "token", String.valueOf(plan.token()));
      json.append('}');
    };
  }

  private Object inputs() {
    return (JsonWritable) json -> {
      json.append('{');
      Input[] inputs = Input.values();
      for (int i = 0; i < inputs.length; i++) {
        if (i != 0) {
          json.append(',');
        }
        field(json, inputs[i].name(), baritone.getInputOverrideHandler().isInputForcedDown(inputs[i]));
      }
      json.append('}');
    };
  }

  private Object physicalInputs() {
    return (JsonWritable) json -> {
      json.append('{');
      Input[] inputs = Input.values();
      for (int i = 0; i < inputs.length; i++) {
        if (i != 0) {
          json.append(',');
        }
        field(json, inputs[i].name(), physicalInput(inputs[i]));
      }
      json.append('}');
    };
  }

  private boolean physicalInput(Input input) {
    var options = ctx.minecraft().options;
    return switch (input) {
      case MOVE_FORWARD -> options.keyUp.isDown();
      case MOVE_BACK -> options.keyDown.isDown();
      case MOVE_LEFT -> options.keyLeft.isDown();
      case MOVE_RIGHT -> options.keyRight.isDown();
      case CLICK_LEFT -> options.keyAttack.isDown();
      case CLICK_RIGHT -> options.keyUse.isDown();
      case JUMP -> options.keyJump.isDown();
      case SNEAK -> options.keyShift.isDown();
      case SPRINT -> options.keySprint.isDown();
    };
  }

  private Object waterState(BlockPos feet) {
    return (JsonWritable) json -> {
      json.append('{');
      field(json, "feetWater", MovementHelper.isWater(ctx, feet)).append(',');
      field(json, "belowWater", MovementHelper.isWater(ctx, feet.below())).append(',');
      field(json, "aboveWater", MovementHelper.isWater(ctx, feet.above())).append(',');
      field(json, "deepWater", MovementHelper.isDeepWater(ctx, feet)).append(',');
      double surfaceY = waterSurfaceY(feet);
      field(json, "surfaceY", Double.isNaN(surfaceY) ? null : surfaceY).append(',');
      field(json, "clearance", Double.isNaN(surfaceY) ? null : playerEyeClearance(surfaceY));
      json.append('}');
    };
  }

  private Object vehicle(Entity vehicle) {
    if (vehicle == null) {
      return null;
    }
    return (JsonWritable) json -> {
      json.append('{');
      field(json, "type", BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString()).append(',');
      field(json, "pos", vehicle.position()).append(',');
      field(json, "velocity", vehicle.getDeltaMovement()).append(',');
      field(json, "horizontalSpeed", horizontalSpeed(vehicle.getDeltaMovement())).append(',');
      field(json, "yaw", vehicle.getYRot()).append(',');
      field(json, "pitch", vehicle.getXRot()).append(',');
      field(json, "controlled", vehicle == ctx.player().getControlledVehicle()).append(',');
      if (vehicle instanceof AbstractBoat boat) {
        field(json, "boatLeftPaddle", boat.getPaddleState(AbstractBoat.PADDLE_LEFT)).append(',');
        field(json, "boatRightPaddle", boat.getPaddleState(AbstractBoat.PADDLE_RIGHT));
      } else {
        field(json, "boatLeftPaddle", null).append(',');
        field(json, "boatRightPaddle", null);
      }
      json.append('}');
    };
  }

  private static int boatItems(LocalPlayer player) {
    int count = 0;
    for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
      if (stack.getItem() instanceof BoatItem) {
        count += stack.getCount();
      }
    }
    return count;
  }

  private Object soundings(BlockPos feet) {
    return (JsonWritable) json -> {
      json.append('[');
      boolean first = true;
      for (int dz = -SOUNDING_RADIUS; dz <= SOUNDING_RADIUS; dz++) {
        for (int dx = -SOUNDING_RADIUS; dx <= SOUNDING_RADIUS; dx++) {
          if (!first) {
            json.append(',');
          }
          first = false;
          sounding(json, feet, dx, dz);
        }
      }
      json.append(']');
    };
  }

  private void sounding(StringBuilder json, BlockPos feet, int dx, int dz) {
    int x = feet.getX() + dx;
    int z = feet.getZ() + dz;
    int waterY = nearestWaterY(x, feet.getY(), z);
    json.append('{');
    field(json, "dx", dx).append(',');
    field(json, "dz", dz).append(',');
    field(json, "x", x).append(',');
    field(json, "z", z).append(',');
    if (waterY == Integer.MIN_VALUE) {
      field(json, "water", false).append(',');
      field(json, "surfaceY", null).append(',');
      field(json, "floorY", null).append(',');
      field(json, "depth", 0);
    } else {
      int surfaceY = surfaceY(x, waterY, z);
      int floorY = floorY(x, waterY, z);
      field(json, "water", true).append(',');
      field(json, "sampleY", waterY).append(',');
      field(json, "surfaceY", surfaceY).append(',');
      field(json, "floorY", floorY).append(',');
      field(json, "depth", surfaceY - floorY - 1);
    }
    json.append('}');
  }

  private int nearestWaterY(int x, int y, int z) {
    int minY = Math.max(ctx.world().getMinY(), y - SOUNDING_SCAN_DOWN);
    int maxY = Math.min(ctx.world().getMaxY() - 1, y + SOUNDING_SCAN_UP);
    for (int radius = 0; y - radius >= minY || y + radius <= maxY; radius++) {
      if (y + radius <= maxY && MovementHelper.isWater(ctx.world().getBlockState(new BlockPos(x, y + radius, z)))) {
        return y + radius;
      }
      if (radius != 0 && y - radius >= minY && MovementHelper.isWater(ctx.world().getBlockState(new BlockPos(x, y - radius, z)))) {
        return y - radius;
      }
    }
    return Integer.MIN_VALUE;
  }

  private int surfaceY(int x, int y, int z) {
    int maxY = Math.min(ctx.world().getMaxY(), y + SOUNDING_SCAN_UP + SOUNDING_SCAN_DOWN);
    while (y < maxY && MovementHelper.isWater(ctx.world().getBlockState(new BlockPos(x, y, z)))) {
      y++;
    }
    return y;
  }

  private int floorY(int x, int y, int z) {
    int minY = Math.max(ctx.world().getMinY(), y - SOUNDING_SCAN_UP - SOUNDING_SCAN_DOWN);
    while (y >= minY && MovementHelper.isWater(ctx.world().getBlockState(new BlockPos(x, y, z)))) {
      y--;
    }
    return y;
  }

  private double waterSurfaceY(BlockPos feet) {
    if (!MovementHelper.isWater(ctx, feet)) {
      return Double.NaN;
    }
    BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos(feet.getX(), feet.getY(), feet.getZ());
    int maxY = ctx.world().getMaxY();
    while (scan.getY() < maxY && MovementHelper.isWater(ctx, scan)) {
      scan.move(Direction.UP);
    }
    return scan.getY();
  }

  private double playerEyeClearance(double surfaceY) {
    return ctx.player().getEyeY() - surfaceY;
  }

  private void close() {
    BufferedWriter writer = out;
    out = null;
    try {
      if (writer != null) {
        writer.close();
      }
    } catch (IOException ignored) {
    }
  }

  private static double horizontalSpeed(Vec3 v) {
    return Math.hypot(v.x, v.z);
  }

  private static StringBuilder field(StringBuilder json, String name, Object value) {
    quote(json, name).append(':');
    value(json, value);
    return json;
  }

  private static void value(StringBuilder json, Object value) {
    switch (value) {
      case null -> json.append("null");
      case JsonWritable writable -> writable.write(json);
      case Number n -> json.append(n);
      case Boolean b -> json.append(b);
      case Enum<?> e -> quote(json, e.name());
      case BlockPos pos -> blockPos(json, pos);
      case Vec3 vec -> vec3(json, vec);
      case TransportControl control -> transportControl(json, control);
      default -> quote(json, String.valueOf(value));
    }
  }

  private static void transportControl(StringBuilder json, TransportControl control) {
    json.append('{');
    field(json, "movement", control.movement()).append(',');
    field(json, "status", control.status()).append(',');
    field(json, "targetYaw", control.targetYaw()).append(',');
    field(json, "targetPitch", control.targetPitch()).append(',');
    field(json, "forceRotations", control.forceRotations());
    json.append('}');
  }

  private static void blockPos(StringBuilder json, BlockPos pos) {
    json.append('{');
    field(json, "x", pos.getX()).append(',');
    field(json, "y", pos.getY()).append(',');
    field(json, "z", pos.getZ());
    json.append('}');
  }

  private static void vec3(StringBuilder json, Vec3 vec) {
    json.append('{');
    field(json, "x", vec.x).append(',');
    field(json, "y", vec.y).append(',');
    field(json, "z", vec.z);
    json.append('}');
  }

  private static StringBuilder quote(StringBuilder json, String value) {
    json.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> json.append(c);
      }
    }
    return json.append('"');
  }

  private interface JsonWritable {
    void write(StringBuilder json);
  }

}
