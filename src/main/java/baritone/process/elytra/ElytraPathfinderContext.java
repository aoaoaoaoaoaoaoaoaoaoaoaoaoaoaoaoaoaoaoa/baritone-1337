package baritone.process.elytra;

import baritone.api.event.events.BlockChangeEvent;
import baritone.transport.TransportKind;
import baritone.transport.TransportPlanner;
import baritone.utils.BlockStateInterface;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

public interface ElytraPathfinderContext extends TransportPlanner<ElytraPath> {
  @Override
  default TransportKind kind() {
    return TransportKind.ELYTRA;
  }

  @Override
  default CompletableFuture<ElytraPath> plan(BlockPos src, BlockPos dst) {
    return pathFindAsync(src, dst).thenApply(segment -> new ElytraPath(segment.positions(), segment.finished()));
  }

  Object lock();

  boolean hasChunk(ChunkPos pos);

  void queueCacheCulling(int chunkX, int chunkZ, int maxDistanceBlocks);

  void queueForPacking(LevelChunk chunk);

  void queueBlockUpdate(BlockChangeEvent event);

  CompletableFuture<ElytraPathSegment> pathFindAsync(BlockPos src, BlockPos dst);

  boolean raytrace(double startX, double startY, double startZ, double endX, double endY, double endZ);

  default boolean raytrace(Vec3 start, Vec3 end) {
    return raytrace(start.x, start.y, start.z, end.x, end.y, end.z);
  }

  boolean raytrace(int count, double[] src, double[] dst, int visibility);

  void raytrace(int count, double[] src, double[] dst, boolean[] hitsOut, double[] hitPosOut);

  boolean passable(BlockStateInterface bsi, int x, int y, int z, boolean ignoreLava);

  boolean usesPackedChunks();

  boolean usesTerrainSeed();

  long seed();

  void cancel();

  void destroy();

  final class Visibility {
    public static final int ALL = 0;
    public static final int NONE = 1;
    public static final int ANY = 2;

    private Visibility() {
    }
  }
}
