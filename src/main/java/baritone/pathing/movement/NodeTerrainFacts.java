package baritone.pathing.movement;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class NodeTerrainFacts {
  public int x;
  public int y;
  public int z;
  public BlockState src;
  public BlockState srcDown;
  public Block srcDownBlock;
  public BlockState srcUp2;
  public boolean srcHorizontalWaterMoveThrough;
  public boolean standingOnABlock;
  public boolean srcDownLadderOrVine;

  public void load(CalculationContext context, int x, int y, int z) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.src = context.get(x, y, z);
    BlockState srcUp = context.get(x, y + 1, z);
    this.srcDown = context.get(x, y - 1, z);
    this.srcDownBlock = srcDown.getBlock();
    this.srcUp2 = context.get(x, y + 2, z);
    this.srcHorizontalWaterMoveThrough = MovementHelper.canHorizontalWaterMoveThrough(context, x, y, z, src, srcUp);
    this.standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
    this.srcDownLadderOrVine = srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE;
  }

  public boolean matches(int x, int y, int z) {
    return this.x == x && this.y == y && this.z == z;
  }
}
