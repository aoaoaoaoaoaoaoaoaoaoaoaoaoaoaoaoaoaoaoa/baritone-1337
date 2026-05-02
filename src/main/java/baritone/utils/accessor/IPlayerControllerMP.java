package baritone.utils.accessor;

import net.minecraft.core.BlockPos;

public interface IPlayerControllerMP {

    void setIsHittingBlock(boolean isHittingBlock);

    boolean isHittingBlock();

    BlockPos getCurrentBlock();

    void callSyncCurrentPlayItem();

    void setDestroyDelay(int destroyDelay);
}
