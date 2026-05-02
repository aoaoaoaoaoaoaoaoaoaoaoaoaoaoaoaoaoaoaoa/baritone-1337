package baritone.utils.accessor;

import net.minecraft.client.multiplayer.ClientChunkCache;

public interface IClientChunkProvider {
    ClientChunkCache createThreadSafeCopy();

    IChunkArray extractReferenceArray();
}
