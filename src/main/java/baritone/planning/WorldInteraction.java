package baritone.planning;

import net.minecraft.core.BlockPos;

public sealed interface WorldInteraction permits WorldInteraction.UseItem, WorldInteraction.AttackEntity, WorldInteraction.UseEntity, WorldInteraction.UseBlock {
  record UseItem() implements WorldInteraction {
  }

  record AttackEntity(int entityId) implements WorldInteraction {
  }

  record UseEntity(int entityId) implements WorldInteraction {
  }

  record UseBlock(BlockPos pos) implements WorldInteraction {
  }
}
