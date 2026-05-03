package baritone.behavior;

import baritone.Baritone;
import baritone.api.event.events.TickEvent;
import baritone.api.event.events.WorldEvent;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.movement.IMovement;
import baritone.api.pathing.movement.MovementStatus;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.utils.Rotation;
import baritone.api.utils.input.Input;
import baritone.pathing.movement.MovementHelper;
import baritone.pathing.movement.Movement;
import baritone.pathing.movement.MovementState;
import baritone.pathing.path.PathExecutor;
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
import net.minecraft.world.phys.Vec3;

public final class PlayerTelemetryBehavior extends Behavior {
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final int SOUNDING_RADIUS = 1;
  private static final int SOUNDING_SCAN_UP = 8;
  private static final int SOUNDING_SCAN_DOWN = 16;

  private BufferedWriter out;
  private Path output;
  private long startedNanos;
  private int samples;
  private String lastFailure;
  private MovementControl movementControl;

  public PlayerTelemetryBehavior(Baritone baritone) {
    super(baritone);
  }

  public boolean active() {
    return out != null;
  }

  public Optional<Path> output() {
    return Optional.ofNullable(output);
  }

  public String start() {
    if (active()) {
      return "Player telemetry already active: " + output;
    }
    try {
      Path directory = baritone.getDirectory().resolve("profiles");
      Files.createDirectories(directory);
      output = directory.resolve("player-" + FILE_TIME.format(Instant.now()) + "-" + Long.toUnsignedString(System.nanoTime(), 36) + ".jsonl");
      out = Files.newBufferedWriter(output, StandardCharsets.UTF_8);
      startedNanos = System.nanoTime();
      samples = 0;
      lastFailure = null;
      event("start");
      return "Player telemetry: " + output;
    } catch (IOException e) {
      lastFailure = e.toString();
      output = null;
      out = null;
      return "Failed to start player telemetry: " + lastFailure;
    }
  }

  public String stop() {
    if (!active()) {
      return lastFailure == null ? "Player telemetry idle" : "Player telemetry idle; last failure: " + lastFailure;
    }
    Path saved = output;
    event("stop", "samples", samples);
    close();
    return "Saved player telemetry: " + saved;
  }

  public String toggle() {
    return active() ? stop() : start();
  }

  public String status() {
    if (active()) {
      return "Player telemetry active: " + output + " (" + samples + " ticks)";
    }
    return lastFailure == null ? output == null ? "Player telemetry idle" : "Player telemetry idle; last output: " + output : "Player telemetry idle; last failure: " + lastFailure;
  }

  public void recordMovementControl(Movement movement, MovementState state) {
    Optional<Rotation> rotation = state.getTarget().getRotation();
    movementControl = new MovementControl(movement.getClass().getSimpleName(), state.getStatus(), rotation.map(Rotation::getYaw).orElse(null), rotation.map(Rotation::getPitch).orElse(null),
      state.getTarget().hasToForceRotations());
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
      PathExecutor executor = baritone.getPathingBehavior().getCurrent();
      IPath path = executor == null ? null : executor.getPath();
      int pathPosition = executor == null ? -1 : executor.getPosition();
      IMovement movement = path != null && pathPosition >= 0 && pathPosition < path.movements().size() ? path.movements().get(pathPosition) : null;
      MovementControl control = movementControl;
      movementControl = null;
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
      field(json, "air", player.getAirSupply()).append(',');
      field(json, "maxAir", player.getMaxAirSupply()).append(',');
      field(json, "food", player.getFoodData().getFoodLevel()).append(',');
      field(json, "inputs", inputs()).append(',');
      field(json, "water", waterState(ctx.playerFeet())).append(',');
      field(json, "soundings", soundings(ctx.playerFeet())).append(',');
      field(json, "pathing", baritone.getPathingBehavior().isPathing()).append(',');
      field(json, "process", baritone.getPathingControlManager().mostRecentInControl().map(IBaritoneProcess::displayName).orElse(null)).append(',');
      field(json, "command", baritone.getPathingControlManager().mostRecentCommand().map(PathingCommand::toString).orElse(null)).append(',');
      field(json, "movementControl", control).append(',');
      field(json, "pathPosition", pathPosition).append(',');
      field(json, "pathLength", path == null ? null : path.length()).append(',');
      field(json, "movement", movement == null ? null : movement.getClass().getSimpleName()).append(',');
      field(json, "movementSrc", movement == null ? null : movement.getSrc()).append(',');
      field(json, "movementDest", movement == null ? null : movement.getDest());
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
    field(json, "scope", "player_tick").append(',');
    field(json, "type", type).append(',');
    field(json, "elapsedNanos", System.nanoTime() - startedNanos);
    return json;
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
    movementControl = null;
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
      default -> quote(json, String.valueOf(value));
    }
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

  private record MovementControl(String movement, MovementStatus status, Float targetYaw, Float targetPitch, boolean forceRotations) implements JsonWritable {
    @Override
    public void write(StringBuilder json) {
      json.append('{');
      field(json, "movement", movement).append(',');
      field(json, "status", status).append(',');
      field(json, "targetYaw", targetYaw).append(',');
      field(json, "targetPitch", targetPitch).append(',');
      field(json, "forceRotations", forceRotations);
      json.append('}');
    }
  }
}
