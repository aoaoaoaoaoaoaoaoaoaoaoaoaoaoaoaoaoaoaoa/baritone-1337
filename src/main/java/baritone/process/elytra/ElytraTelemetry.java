package baritone.process.elytra;

import baritone.api.process.ElytraLaunchMode;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Rotation;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class ElytraTelemetry implements AutoCloseable {
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private final Path output;
  private final BufferedWriter out;
  private final long startedNanos = System.nanoTime();
  private int tickSamples;

  private ElytraTelemetry(Path output, BufferedWriter out) {
    this.output = output;
    this.out = out;
  }

  static ElytraTelemetry open(Path directory, BlockPos start, BlockPos destination, ElytraLaunchMode launchMode, ElytraFlightPolicy policy) {
    try {
      Files.createDirectories(directory);
      Path output = directory.resolve("elytra-" + FILE_TIME.format(Instant.now()) + "-" + Long.toUnsignedString(System.nanoTime(), 36) + ".jsonl");
      ElytraTelemetry telemetry = new ElytraTelemetry(output, Files.newBufferedWriter(output, StandardCharsets.UTF_8));
      telemetry.event("start", "start", start, "destination", destination, "launchMode", launchMode, "dimension", policy.dimension(), "fireworkPolicy", policy.fireworkPolicy());
      return telemetry;
    } catch (IOException e) {
      return null;
    }
  }

  Path output() {
    return output;
  }

  void path(ElytraPath path, boolean complete) {
    BetterBlockPos first = path.isEmpty() ? null : path.get(0);
    BetterBlockPos last = path.isEmpty() ? null : path.get(path.size() - 1);
    event("path", "size", path.size(), "complete", complete, "first", first, "last", last);
  }

  void tick(ElytraSolverContext context, ElytraSolution solution, boolean landingMode) {
    if (++tickSamples % 20 != 0) {
      return;
    }
    Rotation rotation = solution == null ? null : solution.rotation();
    ElytraControlDecision control = context.control;
    event("tick", "near", context.playerNear, "pathSize", context.path.size(), "landing", landingMode, "position", context.start, "motion", context.motion, "target",
      solution == null ? null : solution.goingTo(), "pitch", rotation == null ? null : rotation.getPitch(), "yaw", rotation == null ? null : rotation.getYaw(), "solved",
      solution != null && solution.solvedPitch(), "pitchSource", solution == null ? null : solution.pitchSource(), "forceFirework", solution != null && solution.forceUseFirework(), "fireworkReason",
      solution == null ? null : solution.fireworkReason(), "controlMode", control.mode(), "controlPitch", control.pitch(), "controlTargetY", control.targetY(), "controlFloorY", control.floorY(),
      "controlPhaseTicks", control.phaseTicks(), "controlClearance", control.clearance(), "controlDebt", control.debt(), "controlHorizontalSpeed", control.horizontalSpeed(), "controlVerticalSpeed",
      control.verticalSpeed(), "controlFirework", control.firework(), "controlFireworkReason", control.fireworkReason());
  }

  void event(String type, Object... fields) {
    try {
      StringBuilder json = new StringBuilder(512);
      json.append('{');
      field(json, "schema", 1).append(',');
      field(json, "scope", "elytra_leg").append(',');
      field(json, "type", type).append(',');
      field(json, "elapsedNanos", System.nanoTime() - startedNanos);
      for (int i = 0; i + 1 < fields.length; i += 2) {
        json.append(',');
        field(json, String.valueOf(fields[i]), fields[i + 1]);
      }
      json.append("}\n");
      out.write(json.toString());
      out.flush();
    } catch (IOException ignored) {
    }
  }

  @Override
  public void close() {
    event("close");
    try {
      out.close();
    } catch (IOException ignored) {
    }
  }

  private static StringBuilder field(StringBuilder json, String name, Object value) {
    quote(json, name).append(':');
    value(json, value);
    return json;
  }

  private static void value(StringBuilder json, Object value) {
    switch (value) {
      case null -> json.append("null");
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
}
