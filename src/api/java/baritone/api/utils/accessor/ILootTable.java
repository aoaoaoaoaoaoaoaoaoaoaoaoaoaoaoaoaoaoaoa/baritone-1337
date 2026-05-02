package baritone.api.utils.accessor;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;

public interface ILootTable {

    ObjectArrayList<ItemStack> invokeGetRandomItems(LootContext context);

}
