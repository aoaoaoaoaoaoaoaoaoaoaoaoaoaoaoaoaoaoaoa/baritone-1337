package baritone.process.elytra;

import baritone.api.event.events.BlockChangeEvent;
import baritone.api.utils.BetterBlockPos;
import baritone.api.utils.IPlayerContext;
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BlockStateInterface;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AirBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class LoadedWorldElytraPathfinderContext implements ElytraPathfinderContext {
  private static final int WAYPOINT_SPACING = 32;
  private static final int LOAD_PROBE_SPACING = 16;
  private static final int TERRAIN_CORRIDOR_RADIUS = 16;

  private final IPlayerContext ctx;
  private final ElytraFlightPolicy policy;

  LoadedWorldElytraPathfinderContext(IPlayerContext ctx, ElytraFlightPolicy policy) {
    this.ctx = ctx;
    this.policy = policy;
  }

  @Override
  public Object lock() {
    return this;
  }

  @Override
  public boolean hasChunk(ChunkPos pos) {
    return ctx.world().getChunkSource().hasChunk(pos.x(), pos.z());
  }

  @Override
  public void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks) {
  }

  @Override
  public void queueForPacking(LevelChunk chunk) {
  }

  @Override
  public void queueBlockUpdate(BlockChangeEvent event) {
  }

  @Override
  public CompletableFuture<ElytraPathSegment> pathFindAsync(BlockPos src, BlockPos dst) {
    CompletableFuture<ElytraPathSegment> future = new CompletableFuture<>();
    ctx.minecraft().execute(() -> {
      try {
        future.complete(route(src, dst));
      } catch (Throwable throwable) {
        future.completeExceptionally(throwable);
      }
    });
    return future;
  }

  private ElytraPathSegment route(BlockPos src, BlockPos dst) {
    List<BetterBlockPos> points = new ArrayList<>();
    points.add(new BetterBlockPos(src));

    double dx = dst.getX() - src.getX();
    double dz = dst.getZ() - src.getZ();
    double flatDistance = Math.hypot(dx, dz);
    int terrainCeiling = loadedTerrainCeiling(src, dst, flatDistance);
    int cruiseY = policy.cruiseY(src.getY(), dst.getY(), terrainCeiling);

    if (flatDistance < 1) {
      points.add(new BetterBlockPos(dst));
      return new ElytraPathSegment(true, dedupe(points));
    }

    boolean finished = true;
    int steps = Math.max(1, Mth.ceil(flatDistance / WAYPOINT_SPACING));
    for (int i = 1; i <= steps; i++) {
      double t = (double) i / steps;
      int x = Mth.floor(src.getX() + dx * t);
      int z = Mth.floor(src.getZ() + dz * t);
      if (!hasChunkAtBlock(x, z)) {
        finished = false;
        break;
      }
      int y = i == steps ? dst.getY() : cruiseY;
      points.add(new BetterBlockPos(x, y, z));
    }

    if (finished && !points.get(points.size() - 1).equals(dst)) {
      points.add(new BetterBlockPos(dst));
    }
    return new ElytraPathSegment(finished, dedupe(points));
  }

  private int loadedTerrainCeiling(BlockPos src, BlockPos dst, double flatDistance) {
    if (flatDistance < 1) {
      return Math.max(src.getY(), dst.getY());
    }
    Level world = ctx.world();
    int steps = Math.max(1, Mth.ceil(flatDistance / LOAD_PROBE_SPACING));
    int max = Math.max(src.getY(), dst.getY());
    for (int i = 0; i <= steps; i++) {
      double t = (double) i / steps;
      int x = Mth.floor(src.getX() + (dst.getX() - src.getX()) * t);
      int z = Mth.floor(src.getZ() + (dst.getZ() - src.getZ()) * t);
      if (!hasChunkAtBlock(x, z)) {
        break;
      }
      max = Math.max(max, corridorCeiling(world, x, z));
    }
    return max;
  }

  private int corridorCeiling(Level world, int x, int z) {
    int max = policy.minY();
    for (int dx = -TERRAIN_CORRIDOR_RADIUS; dx <= TERRAIN_CORRIDOR_RADIUS; dx += 8) {
      for (int dz = -TERRAIN_CORRIDOR_RADIUS; dz <= TERRAIN_CORRIDOR_RADIUS; dz += 8) {
        int px = x + dx;
        int pz = z + dz;
        if (hasChunkAtBlock(px, pz)) {
          max = Math.max(max, world.getHeight(Heightmap.Types.MOTION_BLOCKING, px, pz));
        }
      }
    }
    return max;
  }

  @Override
  public boolean raytrace(double startX, double startY, double startZ, double endX, double endY, double endZ) {
    Vec3 start = new Vec3(startX, startY, startZ);
    Vec3 end = new Vec3(endX, endY, endZ);
    if (!loadedAlong(start, end)) {
      return false;
    }
    return ctx.world().clip(new ClipContext(start, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, ctx.player())).getType() == HitResult.Type.MISS;
  }

  @Override
  public boolean raytrace(int count, double[] src, double[] dst, int visibility) {
    boolean anyClear = false;
    for (int i = 0; i < count; i++) {
      boolean clear = raytrace(src[i * 3], src[i * 3 + 1], src[i * 3 + 2], dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
      anyClear |= clear;
      if (visibility == Visibility.ALL && !clear) {
        return false;
      }
      if (visibility == Visibility.NONE && clear) {
        return false;
      }
    }
    return visibility == Visibility.ANY ? anyClear : true;
  }

  @Override
  public void raytrace(int count, double[] src, double[] dst, boolean[] hitsOut, double[] hitPosOut) {
    for (int i = 0; i < count; i++) {
      boolean clear = raytrace(src[i * 3], src[i * 3 + 1], src[i * 3 + 2], dst[i * 3], dst[i * 3 + 1], dst[i * 3 + 2]);
      hitsOut[i] = !clear;
      if (hitPosOut != null) {
        hitPosOut[i * 3] = dst[i * 3];
        hitPosOut[i * 3 + 1] = dst[i * 3 + 1];
        hitPosOut[i * 3 + 2] = dst[i * 3 + 2];
      }
    }
  }

  @Override
  public boolean passable(BlockStateInterface bsi, int x, int y, int z, boolean ignoreLava) {
    if (y < policy.minY() || y >= policy.maxYExclusive()) {
      return false;
    }
    if (!hasChunkAtBlock(x, z)) {
      return false;
    }
    BlockState state = bsi.get0(x, y, z);
    return state.getBlock() instanceof AirBlock || (ignoreLava && MovementHelper.isLava(state));
  }

  @Override
  public boolean usesPackedChunks() {
    return false;
  }

  @Override
  public boolean usesTerrainSeed() {
    return false;
  }

  @Override
  public long seed() {
    return 0;
  }

  @Override
  public void cancel() {
  }

  @Override
  public void destroy() {
  }

  private boolean loadedAlong(Vec3 start, Vec3 end) {
    double distance = start.distanceTo(end);
    int steps = Math.max(1, Mth.ceil(distance / LOAD_PROBE_SPACING));
    for (int i = 0; i <= steps; i++) {
      double t = (double) i / steps;
      int x = Mth.floor(start.x + (end.x - start.x) * t);
      int z = Mth.floor(start.z + (end.z - start.z) * t);
      if (!hasChunkAtBlock(x, z)) {
        return false;
      }
    }
    return true;
  }

  private boolean hasChunkAtBlock(int x, int z) {
    return ctx.world().getChunkSource().hasChunk(x >> 4, z >> 4);
  }

  private static List<BetterBlockPos> dedupe(List<BetterBlockPos> points) {
    if (points.size() < 2) {
      return points;
    }
    ArrayList<BetterBlockPos> out = new ArrayList<>(points.size());
    BetterBlockPos previous = null;
    for (BetterBlockPos point : points) {
      if (!point.equals(previous)) {
        out.add(point);
      }
      previous = point;
    }
    return out;
  }
}
