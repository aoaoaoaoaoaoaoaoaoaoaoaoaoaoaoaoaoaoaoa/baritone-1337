package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.IPlayerContext;
import baritone.cache.CachedRegion;
import baritone.cache.WorldData;
import baritone.utils.accessor.IClientChunkProvider;
import baritone.utils.pathing.BetterWorldBorder;
import baritone.utils.pathing.ChunkFactState;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public class BlockStateInterface {

  private final ClientChunkCache provider;
  private final WorldData worldData;
  protected final Level world;
  public final BlockPos.MutableBlockPos isPassableBlockPos;
  public final BlockGetter access;
  public final BetterWorldBorder worldBorder;
  private final int minY;
  private final int height;

  private LevelChunk prev = null;
  private CachedRegion prevCached = null;

  private final boolean useTheRealWorld;

  private static final BlockState AIR = Blocks.AIR.defaultBlockState();

  public BlockStateInterface(IPlayerContext ctx) {
    this(ctx, false);
  }

  public BlockStateInterface(IPlayerContext ctx, boolean copyLoadedChunks) {
    this.world = ctx.world();
    this.worldBorder = new BetterWorldBorder(world.getWorldBorder());
    this.worldData = (WorldData) ctx.worldData();
    if (copyLoadedChunks) {
      this.provider = ((IClientChunkProvider) world.getChunkSource()).createThreadSafeCopy();
    } else {
      this.provider = (ClientChunkCache) world.getChunkSource();
    }
    this.useTheRealWorld = !Baritone.settings().pathThroughCachedOnly.value;
    this.minY = world.dimensionType().minY();
    this.height = world.dimensionType().height();
    if (!ctx.minecraft().isSameThread()) {
      throw new IllegalStateException("BlockStateInterface must be constructed on the main thread");
    }
    this.isPassableBlockPos = new BlockPos.MutableBlockPos();
    this.access = new BlockStateInterfaceAccessWrapper(this);
  }

  public ChunkFactState chunkFactState(int blockX, int blockZ) {
    if (livePathingChunk(blockX, blockZ) != null) {
      return ChunkFactState.LIVE;
    }
    return cachedChunk(blockX, blockZ) ? ChunkFactState.CACHED : ChunkFactState.ABSENT;
  }

  public boolean hasLiveChunk(int blockX, int blockZ) {
    return liveChunk(blockX, blockZ) != null;
  }

  public boolean hasLivePathingData(int blockX, int blockZ) {
    return chunkFactState(blockX, blockZ).live();
  }

  public boolean hasPathingData(int blockX, int blockZ) {
    return chunkFactState(blockX, blockZ).pathingData();
  }

  public static Block getBlock(IPlayerContext ctx, BlockPos pos) { // won't be called from the pathing thread because the pathing thread doesn't make a single blockpos pog
    return get(ctx, pos).getBlock();
  }

  public static BlockState get(IPlayerContext ctx, BlockPos pos) {
    return new BlockStateInterface(ctx).get0(pos.getX(), pos.getY(), pos.getZ()); // immense iq
    // can't just do world().get because that doesn't work for out of bounds
    // and toBreak and stuff fails when the movement is instantiated out of load range but it's not able to BlockStateInterface.get what it's going to walk on
  }

  public BlockState get0(BlockPos pos) {
    return get0(pos.getX(), pos.getY(), pos.getZ());
  }

  public BlockState get0(int x, int y, int z) { // Mickey resigned
    y -= minY;
    // Invalid vertical position
    if (y < 0 || y >= height) {
      return AIR;
    }

    LevelChunk live = livePathingChunk(x, z);
    if (live != null) {
      return getFromChunk(live, x, y, z);
    }
    if (!Baritone.settings().chunkCaching.value) {
      return AIR;
    }
    // same idea here, skip the Long2ObjectOpenHashMap.get if at all possible
    // except here, it's 512x512 tiles instead of 16x16, so even better repetition
    CachedRegion cached = cachedRegion(x, z);
    if (cached == null) {
      return AIR;
    }
    BlockState type = cached.getBlock(x & 511, y + minY, z & 511);
    if (type == null) {
      return AIR;
    }
    return type;
  }

  private LevelChunk livePathingChunk(int x, int z) {
    if (!useTheRealWorld) {
      return null;
    }
    return liveChunk(x, z);
  }

  private LevelChunk liveChunk(int x, int z) {
    LevelChunk cached = prev;
    if (cached != null && cached.getPos().x() == x >> 4 && cached.getPos().z() == z >> 4) {
      return cached;
    }
    LevelChunk chunk = provider.getChunk(x >> 4, z >> 4, ChunkStatus.FULL, false);
    if (chunk == null || chunk.isEmpty()) {
      return null;
    }
    prev = chunk;
    return chunk;
  }

  private boolean cachedChunk(int x, int z) {
    CachedRegion region = cachedRegion(x, z);
    return region != null && region.isCached(x & 511, z & 511);
  }

  private CachedRegion cachedRegion(int x, int z) {
    if (!Baritone.settings().chunkCaching.value) {
      return null;
    }
    CachedRegion prevRegion = prevCached;
    if (prevRegion != null && prevRegion.getX() == x >> 9 && prevRegion.getZ() == z >> 9) {
      return prevRegion;
    }
    if (worldData == null) {
      return null;
    }
    prevRegion = worldData.cache.getRegion(x >> 9, z >> 9);
    if (prevRegion == null) {
      return null;
    }
    prevCached = prevRegion;
    return prevRegion;
  }

  // get the block at x,y,z from this chunk WITHOUT creating a single blockpos object
  public static BlockState getFromChunk(LevelChunk chunk, int x, int y, int z) {
    LevelChunkSection section = chunk.getSections()[y >> 4];
    if (section.hasOnlyAir()) {
      return AIR;
    }
    return section.getBlockState(x & 15, y & 15, z & 15);
  }
}
