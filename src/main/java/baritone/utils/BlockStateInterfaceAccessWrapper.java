package baritone.utils;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * @author Brady
 * @since 11/5/2019
 */
@SuppressWarnings("NullableProblems")
public final class BlockStateInterfaceAccessWrapper implements BlockGetter {

  private final BlockStateInterface bsi;

  BlockStateInterfaceAccessWrapper(BlockStateInterface bsi) {
    this.bsi = bsi;
  }

  @Nullable @Override
  public BlockEntity getBlockEntity(BlockPos pos) {
    return null;
  }

  @Override
  public BlockState getBlockState(BlockPos pos) {
    // BlockStateInterface#get0(BlockPos) btfo!
    return this.bsi.get0(pos.getX(), pos.getY(), pos.getZ());
  }

  @Override
  public FluidState getFluidState(BlockPos blockPos) {
    return getBlockState(blockPos).getFluidState();
  }

  @Override
  public int getHeight() { return bsi.world.getHeight(); }

  @Override
  public int getMinY() { return bsi.world.getMinY(); }

}
