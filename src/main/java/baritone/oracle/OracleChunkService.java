package baritone.oracle;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

final class OracleChunkService {
  private final ServerLevel level;
  private final int minY;
  private final int height;
  private final Long2ObjectOpenHashMap<OracleChunk> snapshots = new Long2ObjectOpenHashMap<>();
  private int requestedChunks;
  private int snapshottedChunks;

  OracleChunkService(ServerLevel level) {
    this.level = level;
    this.minY = level.dimensionType().minY();
    this.height = level.dimensionType().height();
  }

  OracleChunk snapshotFullChunk(int chunkX, int chunkZ) {
    long key = ChunkPos.pack(chunkX, chunkZ);
    OracleChunk cached = snapshots.get(key);
    if (cached != null) {
      return cached;
    }
    requestedChunks++;
    ChunkAccess access = level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
    if (!(access instanceof LevelChunk chunk)) {
      throw new IllegalStateException("FULL chunk request for " + chunkX + "," + chunkZ + " produced " + access);
    }
    OracleChunk snapshot = OracleChunk.snapshot(chunk, minY, height);
    snapshots.put(key, snapshot);
    snapshottedChunks++;
    return snapshot;
  }

  int requestedChunks() {
    return requestedChunks;
  }

  int snapshottedChunks() {
    return snapshottedChunks;
  }
}
