package baritone.api.event.events;

import baritone.api.utils.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * @author Brady
 */
public final class BlockChangeEvent {

  private final ChunkPos chunk;
  private final List<Pair<BlockPos, BlockState>> blocks;

  public BlockChangeEvent(ChunkPos pos, List<Pair<BlockPos, BlockState>> blocks) {
    this.chunk = pos;
    this.blocks = blocks;
  }

  public ChunkPos getChunkPos() { return this.chunk; }

  public List<Pair<BlockPos, BlockState>> getBlocks() { return this.blocks; }
}
