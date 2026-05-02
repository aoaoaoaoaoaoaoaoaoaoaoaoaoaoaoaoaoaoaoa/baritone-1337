package baritone.pathing.movement;

import net.minecraft.world.level.block.Block;

import java.util.List;

public record BreakPolicy(boolean allow, List<Block> anyway) {

  public BreakPolicy {
    anyway = List.copyOf(anyway);
  }

  public boolean allows(Block block) {
    return allow || anyway.contains(block);
  }
}
