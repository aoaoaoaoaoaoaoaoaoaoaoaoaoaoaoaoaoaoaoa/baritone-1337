package baritone.utils.accessor;

import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.world.level.chunk.LevelChunk;

public interface IChunkArray {
    void copyFrom(IChunkArray other);

    AtomicReferenceArray<LevelChunk> getChunks();

    int centerX();

    int centerZ();

    int viewDistance();
}
