package baritone.process.elytra;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;

import java.util.OptionalInt;

public final class ElytraFireworks {

  private ElytraFireworks() {
  }

  public static boolean isPlain(ItemStack itemStack) {
    if (itemStack.getItem() != Items.FIREWORK_ROCKET) {
      return false;
    }
    Fireworks fireworks = itemStack.get(DataComponents.FIREWORKS);
    return fireworks != null && fireworks.explosions().isEmpty();
  }

  static boolean isBoosting(ItemStack itemStack) {
    return boost(itemStack).isPresent();
  }

  static OptionalInt boost(ItemStack itemStack) {
    Fireworks fireworks = itemStack.get(DataComponents.FIREWORKS);
    if (fireworks != null && fireworks.explosions().isEmpty()) {
      return OptionalInt.of(fireworks.flightDuration());
    }
    return OptionalInt.empty();
  }
}
