package baritone.pathing.movement;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class NodeTerrainFacts {
  public int x;
  public int y;
  public int z;
  public BlockState src;
  public Block srcBlock;
  public BlockState srcDown;
  public Block srcDownBlock;
  public BlockState srcUp2;
  public Block srcUp2Block;
  public boolean standingOnABlock;
  public boolean srcDownLadderOrVine;
  public boolean srcDownBottomSlab;

  public void load(CalculationContext context, int x, int y, int z) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.src = context.get(x, y, z);
    this.srcBlock = src.getBlock();
    this.srcDown = context.get(x, y - 1, z);
    this.srcDownBlock = srcDown.getBlock();
    this.srcUp2 = context.get(x, y + 2, z);
    this.srcUp2Block = srcUp2.getBlock();
    this.standingOnABlock = MovementHelper.mustBeSolidToWalkOn(context, x, y - 1, z, srcDown);
    this.srcDownLadderOrVine = srcDownBlock == Blocks.LADDER || srcDownBlock == Blocks.VINE;
    this.srcDownBottomSlab = srcDownBlock instanceof SlabBlock && MovementHelper.isBottomSlab(srcDown);
  }

  public boolean matches(int x, int y, int z) {
    return this.x == x && this.y == y && this.z == z;
  }
}
