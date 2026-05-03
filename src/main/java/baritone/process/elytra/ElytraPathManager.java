package baritone.process.elytra;

import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.Helper;
import baritone.api.utils.IPlayerContext;
import baritone.api.utils.SettingsUtil;
import baritone.process.ElytraProcess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

public final class ElytraPathManager {

  interface Host extends Helper {
    IPlayerContext ctx();

    ElytraPathfinderContext pathfinderContext();

    BetterBlockPos destination();

    boolean appendDestination();

    ElytraProcess process();

    boolean clearView(Vec3 start, Vec3 dest, boolean ignoreLava);

    boolean passable(int x, int y, int z, boolean ignoreLava);

    void logVerbose(String message);
  }

  private final Host host;

  public ElytraPath path;
  private boolean completePath;
  private boolean recalculating;
  private int maxPlayerNear;
  private int ticksNearUnchanged;
  private int playerNear;
  private int nextSegmentBackoffTicks;

  ElytraPathManager(Host host) {
    this.host = host;
    clear();
  }

  void tick() {
    updatePlayerNear();
    int prevMaxNear = maxPlayerNear;
    maxPlayerNear = Math.max(maxPlayerNear, playerNear);

    if (maxPlayerNear == prevMaxNear && host.ctx().player().isFallFlying()) {
      ticksNearUnchanged++;
    } else {
      ticksNearUnchanged = 0;
    }

    if (nextSegmentBackoffTicks > 0) {
      nextSegmentBackoffTicks--;
    }
    pathfindAroundObstacles();
    attemptNextSegment();
  }

  public CompletableFuture<Void> pathToDestination() {
    return pathToDestination(host.ctx().playerFeet());
  }

  public CompletableFuture<Void> pathToDestination(BlockPos from) {
    long start = System.nanoTime();
    return path0(from, host.destination(), UnaryOperator.identity()).thenRun(() -> {
      if (path.isEmpty()) {
        host.logDirect("Computed empty elytra path");
        return;
      }
      double distance = path.get(0).distanceTo(path.get(path.size() - 1));
      if (completePath) {
        host.logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
      } else {
        host.logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
      }
    }).whenComplete((result, ex) -> {
      recalculating = false;
      if (ex != null) {
        Throwable cause = ex.getCause();
        if (cause instanceof PathCalculationException) {
          host.logDirect("Failed to compute path to destination");
        } else {
          host.logUnhandledException(cause);
        }
      }
    });
  }

  CompletableFuture<Void> pathRecalcSegment(OptionalInt upToIncl) {
    if (recalculating) {
      throw new IllegalStateException("already recalculating");
    }

    recalculating = true;
    List<BetterBlockPos> after = upToIncl.isPresent() ? path.subList(upToIncl.getAsInt() + 1, path.size()) : Collections.emptyList();
    boolean complete = completePath;

    return path0(host.ctx().playerFeet(), upToIncl.isPresent() ? path.get(upToIncl.getAsInt()) : host.destination(),
      segment -> segment.append(after.stream(), complete || segment.isFinished() && upToIncl.isEmpty())).whenComplete((result, ex) -> {
        recalculating = false;
        if (ex != null) {
          Throwable cause = ex.getCause();
          if (cause instanceof PathCalculationException) {
            host.logDirect("Failed to recompute segment");
          } else {
            host.logUnhandledException(cause);
          }
        }
      });
  }

  void pathNextSegment(int afterIncl) {
    if (recalculating) {
      return;
    }

    recalculating = true;
    List<BetterBlockPos> before = path.subList(0, afterIncl + 1);
    long start = System.nanoTime();
    BetterBlockPos pathStart = path.get(afterIncl);

    path0(pathStart, host.destination(), segment -> segment.prepend(before.stream())).thenRun(() -> {
      int added = path.size() - before.size();
      if (added <= 0) {
        nextSegmentBackoffTicks = 20;
        host.logVerbose("Waiting for more chunks before extending elytra route");
        return;
      }
      int recompute = path.size() - added;
      double distance = path.get(0).distanceTo(path.get(recompute));
      if (completePath) {
        host.logVerbose(String.format("Computed path (%.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
      } else {
        host.logVerbose(String.format("Computed segment (Next %.1f blocks in %.4f seconds)", distance, (System.nanoTime() - start) / 1e9d));
      }
    }).whenComplete((result, ex) -> {
      recalculating = false;
      if (ex != null) {
        Throwable cause = ex.getCause();
        if (cause instanceof PathCalculationException) {
          host.logDirect("Failed to compute next segment");
          if (host.ctx().player().distanceToSqr(pathStart.getCenter()) < 16 * 16) {
            host.logVerbose("Player is near the segment start, therefore repeating this calculation is pointless. Marking as complete");
            completePath = true;
          }
        } else {
          host.logUnhandledException(cause);
        }
      }
    });
  }

  public void clear() {
    path = ElytraPath.emptyPath();
    completePath = true;
    recalculating = false;
    playerNear = 0;
    ticksNearUnchanged = 0;
    maxPlayerNear = 0;
    nextSegmentBackoffTicks = 0;
  }

  public ElytraPath getPath() { return path; }

  public int getNear() { return playerNear; }

  public void updatePlayerNear() {
    if (path.isEmpty()) {
      return;
    }

    int index = playerNear;
    BetterBlockPos pos = host.ctx().playerFeet();
    for (int i = index; i >= Math.max(index - 1000, 0); i -= 10) {
      if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
        index = i;
      }
    }
    for (int i = index; i < Math.min(index + 1000, path.size()); i += 10) {
      if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
        index = i;
      }
    }
    for (int i = index; i >= Math.max(index - 50, 0); i--) {
      if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
        index = i;
      }
    }
    for (int i = index; i < Math.min(index + 50, path.size()); i++) {
      if (path.get(i).distanceSq(pos) < path.get(index).distanceSq(pos)) {
        index = i;
      }
    }
    playerNear = index;
  }

  public boolean isComplete() { return completePath; }

  private CompletableFuture<Void> path0(BlockPos src, BlockPos dst, UnaryOperator<UnpackedSegment> operator) {
    return host.pathfinderContext().pathFindAsync(src, dst).thenApply(UnpackedSegment::from).thenApply(operator).thenAcceptAsync(this::setPath, host.ctx().minecraft()::execute);
  }

  private void setPath(UnpackedSegment segment) {
    List<BetterBlockPos> positions = segment.collect();
    if (host.appendDestination()) {
      BlockPos dest = host.destination();
      BlockPos last = !positions.isEmpty() ? positions.get(positions.size() - 1) : null;
      if (last != null && host.clearView(Vec3.atLowerCornerOf(dest), Vec3.atLowerCornerOf(last), false)) {
        positions.add(new BetterBlockPos(dest));
      } else {
        host.logDirect("unable to land at " + host.destination());
        host.process().landingSpotIsBad(new BetterBlockPos(host.destination()));
      }
    }
    path = new ElytraPath(positions, segment.isFinished());
    completePath = segment.isFinished();
    playerNear = 0;
    ticksNearUnchanged = 0;
    maxPlayerNear = 0;
  }

  private void pathfindAroundObstacles() {
    if (recalculating || nextSegmentBackoffTicks > 0) {
      return;
    }

    int rangeStartIncl = playerNear;
    int rangeEndExcl = playerNear;
    while (rangeEndExcl < path.size() && host.pathfinderContext().hasChunk(ChunkPos.containing(path.get(rangeEndExcl)))) {
      rangeEndExcl++;
    }
    if (rangeStartIncl >= rangeEndExcl) {
      return;
    }
    BetterBlockPos rangeStart = path.get(rangeStartIncl);
    if (!host.passable(rangeStart.x, rangeStart.y, rangeStart.z, false)) {
      return;
    }

    if (host.process().state != ElytraProcess.State.LANDING && ticksNearUnchanged > 100) {
      pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> host.logVerbose("Recalculating segment, no progress in last 100 ticks"));
      ticksNearUnchanged = 0;
      return;
    }

    boolean canSeeAny = false;
    for (int i = rangeStartIncl; i < rangeEndExcl - 1; i++) {
      if (host.clearView(host.ctx().playerFeetAsVec(), path.getVec(i), false) || host.clearView(host.ctx().playerHead(), path.getVec(i), false)) {
        canSeeAny = true;
      }
      if (!host.clearView(path.getVec(i), path.getVec(i + 1), false)) {
        OptionalInt rejoinMainPathAt =
          path.get(rangeEndExcl - 1).distanceSq(host.destination()) < host.ctx().playerFeet().distanceSq(host.destination()) ? OptionalInt.of(rangeEndExcl - 1) : OptionalInt.empty();
        BetterBlockPos blockage = path.get(i);
        double distance = host.ctx().playerFeet().distanceTo(path.get(rejoinMainPathAt.orElse(path.size() - 1)));
        long start = System.nanoTime();
        pathRecalcSegment(rejoinMainPathAt).thenRun(() -> host.logVerbose(String.format("Recalculated segment around path blockage near %s %s %s (next %.1f blocks in %.4f seconds)",
          SettingsUtil.maybeCensor(blockage.x), SettingsUtil.maybeCensor(blockage.y), SettingsUtil.maybeCensor(blockage.z), distance, (System.nanoTime() - start) / 1e9d)));
        return;
      }
    }
    if (!canSeeAny && rangeStartIncl < rangeEndExcl - 2 && host.process().state != ElytraProcess.State.GET_TO_JUMP) {
      pathRecalcSegment(OptionalInt.of(rangeEndExcl - 1)).thenRun(() -> host.logVerbose("Recalculated segment since no path points were visible"));
    }
  }

  private void attemptNextSegment() {
    if (recalculating || nextSegmentBackoffTicks > 0) {
      return;
    }

    int last = path.size() - 1;
    if (!completePath && host.ctx().world().isLoaded(path.get(last))) {
      pathNextSegment(last);
    }
  }
}
