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
  private boolean descendColumnSelected;
  private int descendDestX;
  private int descendDestZ;
  public BlockState descendDestDown;
  public BlockState descendDestFeet;
  public BlockState descendDestHead;
  public BlockState descendAir2;
  public BlockState descendLanding2;
  public BlockState descendLanding3;
  private boolean descendDestDownLoaded;
  private boolean descendDestFeetLoaded;
  private boolean descendDestHeadLoaded;
  private boolean descendAir2Loaded;
  private boolean descendLanding2Loaded;
  private boolean descendLanding3Loaded;

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
    this.descendColumnSelected = false;
  }

  public boolean matches(int x, int y, int z) {
    return this.x == x && this.y == y && this.z == z;
  }

  private void selectDescendColumn(int destX, int destZ) {
    if (descendColumnSelected && descendDestX == destX && descendDestZ == destZ) {
      return;
    }
    descendColumnSelected = true;
    descendDestX = destX;
    descendDestZ = destZ;
    descendDestDownLoaded = false;
    descendDestFeetLoaded = false;
    descendDestHeadLoaded = false;
    descendAir2Loaded = false;
    descendLanding2Loaded = false;
    descendLanding3Loaded = false;
  }

  public BlockState descendDestDown(CalculationContext context, int destX, int destZ) {
    selectDescendColumn(destX, destZ);
    if (!descendDestDownLoaded) {
      descendDestDownLoaded = true;
      descendDestDown = context.get(destX, y - 1, destZ);
    }
    return descendDestDown;
  }

  public BlockState descendDestFeet(CalculationContext context, int destX, int destZ) {
    selectDescendColumn(destX, destZ);
    if (!descendDestFeetLoaded) {
      descendDestFeetLoaded = true;
      descendDestFeet = context.get(destX, y, destZ);
    }
    return descendDestFeet;
  }

  public BlockState descendDestHead(CalculationContext context, int destX, int destZ) {
    selectDescendColumn(destX, destZ);
    if (!descendDestHeadLoaded) {
      descendDestHeadLoaded = true;
      descendDestHead = context.get(destX, y + 1, destZ);
    }
    return descendDestHead;
  }

  public BlockState descendAir2(CalculationContext context, int destX, int destZ) {
    selectDescendColumn(destX, destZ);
    if (!descendAir2Loaded) {
      descendAir2Loaded = true;
      descendAir2 = context.get(destX, y - 2, destZ);
    }
    return descendAir2;
  }

  public BlockState descendLanding2(CalculationContext context, int destX, int destZ) {
    selectDescendColumn(destX, destZ);
    if (!descendLanding2Loaded) {
      descendLanding2Loaded = true;
      descendLanding2 = context.get(destX, y - 3, destZ);
    }
    return descendLanding2;
  }

  public BlockState descendLanding3(CalculationContext context, int destX, int destZ) {
    selectDescendColumn(destX, destZ);
    if (!descendLanding3Loaded) {
      descendLanding3Loaded = true;
      descendLanding3 = context.get(destX, y - 4, destZ);
    }
    return descendLanding3;
  }
}
