package baritone.planning;

import net.minecraft.world.item.Item;

public sealed interface ResourceType permits ResourceType.ItemCount, ResourceType.ItemDurability, ResourceType.VehicleLease, ResourceType.AirReserve, ResourceType.ModificationBudget {

  record ItemCount(Item item) implements ResourceType {
  }

  record ItemDurability(Item item) implements ResourceType {
  }

  record VehicleLease(VehicleKind kind) implements ResourceType {
  }

  record AirReserve() implements ResourceType {
  }

  record ModificationBudget(ModificationKind kind) implements ResourceType {
  }
}
