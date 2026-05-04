package baritone.planning;

import baritone.process.elytra.ElytraFireworks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public record InventorySnapshot(long revision, int hotbarBoatSlot, int hotbarBoatCount, int inventoryBoatCount, int plainFireworks, int equippedElytraDurability, int spareElytraCount) {
  public static InventorySnapshot capture(LocalPlayer player) {
    NonNullList<ItemStack> inv = player.getInventory().getNonEquipmentItems();
    long revision = 0xcbf29ce484222325L;
    int hotbarBoatSlot = -1;
    int hotbarBoatCount = 0;
    int inventoryBoatCount = 0;
    int plainFireworks = 0;
    int spareElytraCount = 0;
    for (int i = 0; i < inv.size(); i++) {
      ItemStack stack = inv.get(i);
      revision = mix(revision, stack);
      if (stack.getItem() instanceof BoatItem) {
        inventoryBoatCount += stack.getCount();
        if (i < 9) {
          hotbarBoatCount += stack.getCount();
          if (hotbarBoatSlot < 0) {
            hotbarBoatSlot = i;
          }
        }
      }
      if (ElytraFireworks.isPlain(stack)) {
        plainFireworks += stack.getCount();
      }
      if (stack.getItem() == Items.ELYTRA && remainingDurability(stack) > 0) {
        spareElytraCount++;
      }
    }
    ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
    revision = mix(revision, chest);
    int equippedElytraDurability = chest.getItem() == Items.ELYTRA ? remainingDurability(chest) : -1;
    return new InventorySnapshot(revision, hotbarBoatSlot, hotbarBoatCount, inventoryBoatCount, plainFireworks, equippedElytraDurability, spareElytraCount);
  }

  public boolean hasHotbarBoat() {
    return hotbarBoatSlot >= 0;
  }

  public int spendableFireworks(int reserve) {
    return Math.max(0, plainFireworks - reserve);
  }

  private static int remainingDurability(ItemStack stack) {
    return stack.getMaxDamage() - stack.getDamageValue();
  }

  private static long mix(long hash, ItemStack stack) {
    hash ^= stack.getItem().hashCode();
    hash *= 0x100000001b3L;
    hash ^= stack.getCount();
    hash *= 0x100000001b3L;
    hash ^= stack.getDamageValue();
    hash *= 0x100000001b3L;
    return hash;
  }
}
