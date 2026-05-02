package com.github.lunatrius.schematica.api;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public interface ISchematic {

    BlockState getBlockState(BlockPos var1);

    int getWidth();

    int getHeight();

    int getLength();
}
