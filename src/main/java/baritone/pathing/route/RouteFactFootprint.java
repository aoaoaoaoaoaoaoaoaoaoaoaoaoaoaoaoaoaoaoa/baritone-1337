package baritone.pathing.route;

import baritone.api.utils.BetterBlockPos;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class RouteFactFootprint {
  public static final RouteFactFootprint EMPTY = new RouteFactFootprint(new LongOpenHashSet(), new LongOpenHashSet());

  private final LongOpenHashSet chunks;
  private final LongOpenHashSet blocks;

  private RouteFactFootprint(LongOpenHashSet chunks, LongOpenHashSet blocks) {
    this.chunks = chunks;
    this.blocks = blocks;
  }

  public static RouteFactFootprint of(RoutePlan route) {
    LongOpenHashSet chunks = new LongOpenHashSet();
    LongOpenHashSet blocks = new LongOpenHashSet();
    for (RouteLeg leg : route.legs()) {
      add(chunks, blocks, leg.src());
      add(chunks, blocks, leg.dest());
      for (BetterBlockPos pos : leg.validPositions()) {
        add(chunks, blocks, pos);
      }
      for (BetterBlockPos pos : leg.renderPositions()) {
        add(chunks, blocks, pos);
      }
    }
    return chunks.isEmpty() ? EMPTY : new RouteFactFootprint(chunks, blocks);
  }

  public boolean touches(ChunkPos chunk) {
    return chunks.contains(ChunkPos.pack(chunk.x(), chunk.z()));
  }

  public boolean touches(BlockPos block) {
    return blocks.contains(block.asLong());
  }

  public boolean empty() {
    return chunks.isEmpty();
  }

  private static void add(LongOpenHashSet chunks, LongOpenHashSet blocks, BetterBlockPos pos) {
    chunks.add(ChunkPos.pack(pos.x >> 4, pos.z >> 4));
    blocks.add(pos.asLong());
    blocks.add(BlockPos.asLong(pos.x, pos.y + 1, pos.z));
    blocks.add(BlockPos.asLong(pos.x, pos.y - 1, pos.z));
  }
}
