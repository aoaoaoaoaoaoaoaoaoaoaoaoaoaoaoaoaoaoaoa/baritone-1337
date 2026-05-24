package baritone.oracle;

import baritone.utils.BlockStateInterface;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.ChunkFactState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

public final class OracleBlockStateInterface extends BlockStateInterface {
  private final OracleChunkService chunks;

  public OracleBlockStateInterface(ServerLevel level) {
    super(level, new BetterWorldBorder(level.getWorldBorder()), level.dimensionType().minY(), level.dimensionType().height());
    this.chunks = new OracleChunkService(level);
  }

  @Override
  public BlockState get0(int x, int y, int z) {
    return chunks.snapshotFullChunk(x >> 4, z >> 4).state(x & 15, y, z & 15);
  }

  @Override
  public ChunkFactState chunkFactState(int blockX, int blockZ) {
    chunks.snapshotFullChunk(blockX >> 4, blockZ >> 4);
    return ChunkFactState.LIVE;
  }

  @Override
  public boolean hasLiveChunk(int blockX, int blockZ) {
    chunks.snapshotFullChunk(blockX >> 4, blockZ >> 4);
    return true;
  }

  @Override
  public boolean hasLivePathingData(int blockX, int blockZ) {
    return hasLiveChunk(blockX, blockZ);
  }

  @Override
  public boolean hasPathingData(int blockX, int blockZ) {
    return hasLiveChunk(blockX, blockZ);
  }

  public int requestedChunks() {
    return chunks.requestedChunks();
  }

  public int snapshottedChunks() {
    return chunks.snapshottedChunks();
  }
}
