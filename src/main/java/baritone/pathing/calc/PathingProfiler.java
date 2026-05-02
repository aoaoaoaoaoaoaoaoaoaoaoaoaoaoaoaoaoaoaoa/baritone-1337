package baritone.pathing.calc;

import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.PathCalculationResult;
import baritone.pathing.movement.Moves;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class PathingProfiler {
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final String SCOPE = "single_path_calculation";
  private static final String ARMED_MESSAGE = "Armed path microprofiler for the next A* calculation segment. Output will be written under .minecraft/baritone/profiles/";

  private final Path outputDirectory;
  private int nextSequence;
  private boolean pending;
  private Active active;
  private Path lastOutput;
  private IOException lastFailure;

  public PathingProfiler(Path outputDirectory) {
    this.outputDirectory = outputDirectory;
  }

  public synchronized String armNext() {
    if (active != null) {
      return "A path microprofile is already active; it will be saved when the calculation finishes";
    }
    pending = true;
    lastFailure = null;
    return ARMED_MESSAGE;
  }

  public synchronized String toggleNext() {
    if (active != null) {
      return "A path microprofile is already active; it will be saved when the calculation finishes";
    }
    if (pending) {
      pending = false;
      return "Canceled pending path microprofile";
    }
    pending = true;
    lastFailure = null;
    return ARMED_MESSAGE;
  }

  public synchronized String cancelPending() {
    if (active != null) {
      return "A path microprofile is already active; it will be saved when the calculation finishes";
    }
    if (!pending) {
      return "Path profiler was not armed";
    }
    pending = false;
    return "Canceled pending path microprofile";
  }

  public synchronized String status() {
    if (active != null) {
      return "Path microprofiler is active for calculation segment #" + active.sequence;
    }
    if (pending) {
      return "Path microprofiler is armed for the next A* calculation segment";
    }
    if (lastFailure != null) {
      return "Path profiler idle; last save failed: " + lastFailure;
    }
    if (lastOutput != null) {
      return "Path profiler idle; last output: " + lastOutput;
    }
    return "Path profiler idle";
  }

  public synchronized Optional<Path> lastOutput() {
    return Optional.ofNullable(lastOutput);
  }

  Active begin(BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, long primaryTimeout, long failureTimeout) {
    synchronized (this) {
      if (!pending || active != null) {
        return null;
      }
      pending = false;
      active = new Active(this, ++nextSequence, realStart, startX, startY, startZ, goal, primaryTimeout, failureTimeout);
      return active;
    }
  }

  private void finish(Active active, PathCalculationResult result) {
    Path output = outputDirectory.resolve("path-" + FILE_TIME.format(active.startedAt) + "-" + active.sequence + ".json");
    try {
      Files.createDirectories(outputDirectory);
      Files.writeString(output, active.render(result, output));
      synchronized (this) {
        if (this.active == active) {
          this.active = null;
        }
        this.lastOutput = output;
        this.lastFailure = null;
      }
    } catch (IOException e) {
      synchronized (this) {
        if (this.active == active) {
          this.active = null;
        }
        this.lastFailure = e;
      }
    }
  }

  public static final class Active {
    private final PathingProfiler owner;
    private final int sequence;
    private final BetterBlockPos realStart;
    private final int startX;
    private final int startY;
    private final int startZ;
    private final String goal;
    private final long primaryTimeout;
    private final long failureTimeout;
    private final Instant startedAt;
    private final long startedNanos;
    private final String threadName;
    private final long[] consideredByMove = new long[Moves.values().length];
    private final long[] reachableByMove = new long[Moves.values().length];
    private final long[] lowerBoundPrunedByMove = new long[Moves.values().length];
    private final long[] nanosByMove = new long[Moves.values().length];
    private final long[] maxNanosByMove = new long[Moves.values().length];
    private final Map<String, MoveCounters> extraMoves = new LinkedHashMap<>();
    private int numNodes;
    private int numMovementsConsidered;
    private int numEmptyChunk;
    private int nodeMapSize;
    private long searchLoopNanos;
    private long heapNanos;
    private long nodeMapNanos;
    private long postProcessNanos;
    private long loadedChunkCutoffNanos;
    private long staticCutoffNanos;
    private String searchStopReason = "not_recorded";

    private Active(PathingProfiler owner, int sequence, BetterBlockPos realStart, int startX, int startY, int startZ, Goal goal, long primaryTimeout, long failureTimeout) {
      this.owner = owner;
      this.sequence = sequence;
      this.realStart = realStart;
      this.startX = startX;
      this.startY = startY;
      this.startZ = startZ;
      this.goal = String.valueOf(goal);
      this.primaryTimeout = primaryTimeout;
      this.failureTimeout = failureTimeout;
      this.startedAt = Instant.now();
      this.startedNanos = System.nanoTime();
      this.threadName = Thread.currentThread().getName();
    }

    public void recordMove(Moves move, long nanos, boolean reachable) {
      int ordinal = move.ordinal();
      consideredByMove[ordinal]++;
      if (reachable) {
        reachableByMove[ordinal]++;
      }
      nanosByMove[ordinal] += nanos;
      if (nanos > maxNanosByMove[ordinal]) {
        maxNanosByMove[ordinal] = nanos;
      }
    }

    public void recordMove(String move, long nanos, boolean reachable) {
      extraMoves.computeIfAbsent(move, ignored -> new MoveCounters()).record(nanos, reachable);
    }

    public void recordLowerBoundPrune(Moves move) {
      lowerBoundPrunedByMove[move.ordinal()]++;
    }

    public void recordLowerBoundPrune(String move) {
      extraMoves.computeIfAbsent(move, ignored -> new MoveCounters()).recordLowerBoundPrune();
    }

    public void finishSearchLoop(int numNodes, int numMovementsConsidered, int numEmptyChunk, int nodeMapSize, String stopReason) {
      finishSearchLoop(numNodes, numMovementsConsidered, numEmptyChunk, nodeMapSize, stopReason, 0, 0, 0);
    }

    public void finishSearchLoop(int numNodes, int numMovementsConsidered, int numEmptyChunk, int nodeMapSize, String stopReason, long searchLoopNanos, long heapNanos, long nodeMapNanos) {
      this.numNodes = numNodes;
      this.numMovementsConsidered = numMovementsConsidered;
      this.numEmptyChunk = numEmptyChunk;
      this.nodeMapSize = nodeMapSize;
      this.searchStopReason = stopReason;
      this.searchLoopNanos = searchLoopNanos;
      this.heapNanos = heapNanos;
      this.nodeMapNanos = nodeMapNanos;
    }

    public void finishPathPhases(long postProcessNanos, long loadedChunkCutoffNanos, long staticCutoffNanos) {
      this.postProcessNanos = postProcessNanos;
      this.loadedChunkCutoffNanos = loadedChunkCutoffNanos;
      this.staticCutoffNanos = staticCutoffNanos;
    }

    void finish(PathCalculationResult result) {
      owner.finish(this, result);
    }

    private String render(PathCalculationResult result, Path output) {
      Optional<IPath> path = result.getPath();
      StringBuilder json = new StringBuilder(8192);
      json.append("{\n");
      field(json, "schema", 1).append(",\n");
      field(json, "scope", SCOPE).append(",\n");
      field(json, "sequence", sequence).append(",\n");
      field(json, "output", output.toString()).append(",\n");
      field(json, "startedAt", startedAt.toString()).append(",\n");
      field(json, "durationNanos", System.nanoTime() - startedNanos).append(",\n");
      field(json, "thread", threadName).append(",\n");
      json.append("  \"realStart\": ");
      blockPos(json, realStart.x, realStart.y, realStart.z).append(",\n");
      json.append("  \"start\": ");
      blockPos(json, startX, startY, startZ).append(",\n");
      field(json, "goal", goal).append(",\n");
      field(json, "primaryTimeoutMs", primaryTimeout).append(",\n");
      field(json, "failureTimeoutMs", failureTimeout).append(",\n");
      field(json, "result", result.getType().name()).append(",\n");
      field(json, "pathLength", path.map(IPath::length).orElse(0)).append(",\n");
      field(json, "numNodes", numNodes).append(",\n");
      field(json, "numMovementsConsidered", numMovementsConsidered).append(",\n");
      field(json, "numEmptyChunk", numEmptyChunk).append(",\n");
      field(json, "nodeMapSize", nodeMapSize).append(",\n");
      field(json, "searchStopReason", searchStopReason).append(",\n");
      json.append("  \"phases\": {");
      inlineField(json, "searchLoopNanos", searchLoopNanos).append(", ");
      inlineField(json, "movementEvalNanos", movementEvalNanos()).append(", ");
      inlineField(json, "heapNanos", heapNanos).append(", ");
      inlineField(json, "nodeMapNanos", nodeMapNanos).append(", ");
      inlineField(json, "postProcessNanos", postProcessNanos).append(", ");
      inlineField(json, "loadedChunkCutoffNanos", loadedChunkCutoffNanos).append(", ");
      inlineField(json, "staticCutoffNanos", staticCutoffNanos);
      json.append("},\n");
      json.append("  \"moves\": [\n");
      Moves[] moves = Moves.values();
      boolean first = true;
      for (Moves move : moves) {
        int ordinal = move.ordinal();
        long considered = consideredByMove[ordinal];
        long pruned = lowerBoundPrunedByMove[ordinal];
        if (considered == 0 && pruned == 0) {
          continue;
        }
        if (!first) {
          json.append(",\n");
        }
        first = false;
        long nanos = nanosByMove[ordinal];
        json.append("    {");
        inlineField(json, "name", move.name()).append(", ");
        inlineField(json, "considered", considered).append(", ");
        inlineField(json, "reachable", reachableByMove[ordinal]).append(", ");
        inlineField(json, "blocked", considered - reachableByMove[ordinal]).append(", ");
        inlineField(json, "lowerBoundPruned", pruned).append(", ");
        inlineField(json, "nanos", nanos).append(", ");
        inlineField(json, "maxNanos", maxNanosByMove[ordinal]).append(", ");
        inlineField(json, "avgNanos", String.format(Locale.ROOT, "%.1f", considered == 0 ? 0 : (double) nanos / considered), false);
        json.append("}");
      }
      for (Map.Entry<String, MoveCounters> entry : extraMoves.entrySet()) {
        if (!first) {
          json.append(",\n");
        }
        first = false;
        MoveCounters counters = entry.getValue();
        json.append("    {");
        inlineField(json, "name", entry.getKey()).append(", ");
        inlineField(json, "considered", counters.considered).append(", ");
        inlineField(json, "reachable", counters.reachable).append(", ");
        inlineField(json, "blocked", counters.considered - counters.reachable).append(", ");
        inlineField(json, "lowerBoundPruned", counters.lowerBoundPruned).append(", ");
        inlineField(json, "nanos", counters.nanos).append(", ");
        inlineField(json, "maxNanos", counters.maxNanos).append(", ");
        inlineField(json, "avgNanos", String.format(Locale.ROOT, "%.1f", counters.considered == 0 ? 0 : (double) counters.nanos / counters.considered), false);
        json.append("}");
      }
      json.append("\n  ]\n");
      json.append("}\n");
      return json.toString();
    }

    private static StringBuilder blockPos(StringBuilder json, int x, int y, int z) {
      return json.append("{\"x\": ").append(x).append(", \"y\": ").append(y).append(", \"z\": ").append(z).append("}");
    }

    private static StringBuilder field(StringBuilder json, String name, String value) {
      return json.append("  \"").append(name).append("\": \"").append(escape(value)).append("\"");
    }

    private static StringBuilder field(StringBuilder json, String name, long value) {
      return json.append("  \"").append(name).append("\": ").append(value);
    }

    private static StringBuilder inlineField(StringBuilder json, String name, String value) {
      return inlineField(json, name, value, true);
    }

    private static StringBuilder inlineField(StringBuilder json, String name, String value, boolean quote) {
      json.append("\"").append(name).append("\": ");
      if (quote) {
        return json.append("\"").append(escape(value)).append("\"");
      }
      return json.append(value);
    }

    private static StringBuilder inlineField(StringBuilder json, String name, long value) {
      return json.append("\"").append(name).append("\": ").append(value);
    }

    private static long sum(long[] values) {
      long sum = 0;
      for (long value : values) {
        sum += value;
      }
      return sum;
    }

    private long movementEvalNanos() {
      long sum = sum(nanosByMove);
      for (MoveCounters counters : extraMoves.values()) {
        sum += counters.nanos;
      }
      return sum;
    }

    private static String escape(String raw) {
      StringBuilder escaped = new StringBuilder(raw.length() + 16);
      for (int i = 0; i < raw.length(); i++) {
        char c = raw.charAt(i);
        switch (c) {
          case '\\' -> escaped.append("\\\\");
          case '"' -> escaped.append("\\\"");
          case '\n' -> escaped.append("\\n");
          case '\r' -> escaped.append("\\r");
          case '\t' -> escaped.append("\\t");
          default -> {
            if (c < 0x20) {
              escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
              escaped.append(c);
            }
          }
        }
      }
      return escaped.toString();
    }

    private static final class MoveCounters {
      private long considered;
      private long reachable;
      private long lowerBoundPruned;
      private long nanos;
      private long maxNanos;

      private void record(long nanos, boolean reachable) {
        considered++;
        if (reachable) {
          this.reachable++;
        }
        this.nanos += nanos;
        if (nanos > maxNanos) {
          maxNanos = nanos;
        }
      }

      private void recordLowerBoundPrune() {
        lowerBoundPruned++;
      }
    }
  }
}
