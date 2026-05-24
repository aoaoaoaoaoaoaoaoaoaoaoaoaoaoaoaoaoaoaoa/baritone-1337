package baritone.pathing.movement;

import baritone.api.BaritoneAPI;
import java.util.Set;
import net.minecraft.world.level.block.Block;

public record BreakPolicy(boolean allow, Set<Block> anyway, Set<Block> disallowed, boolean avoidUpdatingFallingBlocks, boolean strictLiquidCheck) {

  public BreakPolicy {
    anyway = Set.copyOf(anyway);
    disallowed = Set.copyOf(disallowed);
  }

  public static BreakPolicy snapshot() {
    var settings = BaritoneAPI.getSettings();
    return new BreakPolicy(settings.allowBreak.value, Set.copyOf(settings.allowBreakAnyway.value), Set.copyOf(settings.blocksToDisallowBreaking.value), settings.avoidUpdatingFallingBlocks.value,
      settings.strictLiquidCheck.value);
  }

  public boolean allows(Block block) {
    return allow || anyway.contains(block);
  }

  public boolean forbids(Block block) {
    return disallowed.contains(block);
  }
}
