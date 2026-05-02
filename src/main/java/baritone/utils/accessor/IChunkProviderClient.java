package baritone.utils.accessor;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.world.level.chunk.LevelChunk;

public interface IChunkProviderClient {

    Long2ObjectMap<LevelChunk> loadedChunks();
}
