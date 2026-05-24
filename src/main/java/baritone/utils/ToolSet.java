package baritone.utils;

import baritone.api.BaritoneAPI;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * A cached list of the best tools on the hotbar for any block
 *
 * @author Avery, Brady, leijurv
 */
public class ToolSet {

  /**
   * A cache mapping a {@link Block} to how long it will take to break
   * with this toolset, given the optimum tool is used.
   */
  private final double[] breakStrengthCache;

  private final List<ItemStack> hotbar;
  private final int selectedSlot;
  private final double potionAmplifier;
  private final boolean autoTool;
  private final boolean useSwordToMine;
  private final boolean itemSaver;
  private final int itemSaverThreshold;
  private final Set<Block> blocksToAvoidBreaking;
  private final double avoidBreakingMultiplier;

  /**
   * Used for evaluating the material cost of a tool.
   * see {@link #getMaterialCost(ItemStack)}
   * Prefer tools with lower material cost (lower index in this list).
   */
  private static final List<TagKey<Item>> materialTagsPriorityList = List.of(ItemTags.WOODEN_TOOL_MATERIALS, ItemTags.STONE_TOOL_MATERIALS, ItemTags.IRON_TOOL_MATERIALS, ItemTags.GOLD_TOOL_MATERIALS,
    ItemTags.DIAMOND_TOOL_MATERIALS, ItemTags.NETHERITE_TOOL_MATERIALS);

  public ToolSet(LocalPlayer player) {
    this(hotbar(player), player.getInventory().getSelectedSlot(), BaritoneAPI.getSettings().considerPotionEffects.value ? potionAmplifier(player) : 1D);
  }

  public ToolSet(List<ItemStack> hotbar) {
    this(hotbar, 0, 1D);
  }

  private ToolSet(List<ItemStack> hotbar, int selectedSlot, double potionAmplifier) {
    this.breakStrengthCache = new double[BuiltInRegistries.BLOCK.size()];
    Arrays.fill(this.breakStrengthCache, Double.NaN);
    this.hotbar = List.copyOf(hotbar);
    this.selectedSlot = Math.clamp(selectedSlot, 0, Math.max(0, this.hotbar.size() - 1));
    this.potionAmplifier = potionAmplifier;
    var settings = BaritoneAPI.getSettings();
    this.autoTool = settings.autoTool.value;
    this.useSwordToMine = settings.useSwordToMine.value;
    this.itemSaver = settings.itemSaver.value;
    this.itemSaverThreshold = settings.itemSaverThreshold.value;
    this.blocksToAvoidBreaking = Set.copyOf(settings.blocksToAvoidBreaking.value);
    this.avoidBreakingMultiplier = settings.avoidBreakingMultiplier.value;
  }

  /**
   * Using the best tool on the hotbar, how fast we can mine this block
   *
   * @param state the blockstate to be mined
   * @return the speed of how fast we'll mine it. 1/(time in ticks)
   */
  public double getStrVsBlock(BlockState state) {
    Block block = state.getBlock();
    int id = BuiltInRegistries.BLOCK.getId(block);
    if (id < 0 || id >= breakStrengthCache.length) {
      return getBestDestructionTime(block);
    }
    double cached = breakStrengthCache[id];
    if (!Double.isNaN(cached)) {
      return cached;
    }
    double computed = getBestDestructionTime(block);
    breakStrengthCache[id] = computed;
    return computed;
  }

  /**
   * Evaluate the material cost of a possible tool.
   * If all else is equal, we want to prefer the tool with the lowest material cost.
   * i.e. we want to prefer a wooden pickaxe over a stone pickaxe, if all else is equal.
   * @param itemStack a possibly empty ItemStack
   * @return values from 0 up
   */
  private int getMaterialCost(ItemStack itemStack) {
    for (int i = 0; i < materialTagsPriorityList.size(); i++) {
      final TagKey<Item> tag = materialTagsPriorityList.get(i);
      if (itemStack.is(tag)) return i;
    }
    return -1;
  }

  public boolean hasSilkTouch(ItemStack stack) {
    ItemEnchantments enchantments = stack.getEnchantments();
    for (Holder<Enchantment> enchant : enchantments.keySet()) {
      // silk touch enchantment is still special cased as affecting block drops
      // not possible to add custom attribute via datapack
      if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
        return true;
      }
    }
    return false;
  }

  /**
   * Calculate which tool on the hotbar is best for mining, depending on an override setting,
   * related to auto tool movement cost, it will either return current selected slot, or the best slot.
   *
   * @param b the blockstate to be mined
   * @return An int containing the index in the tools array that worked best
   */

  public int getBestSlot(Block b, boolean preferSilkTouch) {
    return getBestSlot(b, preferSilkTouch, false);
  }

  public int getBestSlot(Block b, boolean preferSilkTouch, boolean pathingCalculation) {

    /*
    If we actually want know what efficiency our held item has instead of the best one
    possible, this lets us make pathing depend on the actual tool to be used (if auto tool is disabled)
    */
    if (!autoTool && pathingCalculation) {
      return selectedSlot;
    }

    int best = 0;
    double highestSpeed = Double.NEGATIVE_INFINITY;
    int lowestCost = Integer.MIN_VALUE;
    boolean bestSilkTouch = false;
    BlockState blockState = b.defaultBlockState();
    for (int i = 0; i < Math.min(9, hotbar.size()); i++) {
      ItemStack itemStack = hotbar.get(i);
      if (!useSwordToMine && itemStack.getItem().components().has(DataComponents.WEAPON)) {
        continue;
      }

      if (itemSaver && (itemStack.getDamageValue() + itemSaverThreshold) >= itemStack.getMaxDamage() && itemStack.getMaxDamage() > 1) {
        continue;
      }
      double speed = calculateSpeedVsBlock(itemStack, blockState);
      boolean silkTouch = hasSilkTouch(itemStack);
      if (speed > highestSpeed) {
        highestSpeed = speed;
        best = i;
        lowestCost = getMaterialCost(itemStack);
        bestSilkTouch = silkTouch;
      } else if (speed == highestSpeed) {
        int cost = getMaterialCost(itemStack);
        if ((cost < lowestCost && (silkTouch || !bestSilkTouch)) || (preferSilkTouch && !bestSilkTouch && silkTouch)) {
          highestSpeed = speed;
          best = i;
          lowestCost = cost;
          bestSilkTouch = silkTouch;
        }
      }
    }
    return best;
  }

  /**
   * Calculate how effectively a block can be destroyed
   *
   * @param b the blockstate to be mined
   * @return A double containing the destruction ticks with the best tool
   */
  private double getBestDestructionTime(Block b) {
    ItemStack stack = hotbar.isEmpty() ? ItemStack.EMPTY : hotbar.get(getBestSlot(b, false, true));
    return calculateSpeedVsBlock(stack, b.defaultBlockState()) * avoidanceMultiplier(b) * potionAmplifier;
  }

  private double avoidanceMultiplier(Block b) {
    return blocksToAvoidBreaking.contains(b) ? avoidBreakingMultiplier : 1;
  }

  /**
   * Calculates how long would it take to mine the specified block given the best tool
   * in this toolset is used. A negative value is returned if the specified block is unbreakable.
   *
   * @param item  the item to mine it with
   * @param state the blockstate to be mined
   * @return how long it would take in ticks
   */
  public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
    float hardness;
    try {
      hardness = state.getDestroySpeed(null, null);
    } catch (NullPointerException npe) {
      // can't easily determine the hardness so treat it as unbreakable
      return -1;
    }
    if (hardness < 0) {
      return -1;
    }

    float speed = item.getDestroySpeed(state);
    if (speed > 1) {
      final ItemEnchantments itemEnchantments = item.getEnchantments();
      OUTER : for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
        List<EnchantmentAttributeEffect> effects = enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
        for (EnchantmentAttributeEffect e : effects) {
          if (e.attribute().is(Attributes.MINING_EFFICIENCY.unwrapKey().get())) {
            speed += e.amount().calculate(itemEnchantments.getLevel(enchant));
            break OUTER;
          }
        }
      }
    }

    speed /= hardness;
    if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
      return speed / 30;
    } else {
      return speed / 100;
    }
  }

  /**
   * Calculates any modifier to breaking time based on status effects.
   *
   * @return a double to scale block breaking speed.
   */
  private static List<ItemStack> hotbar(LocalPlayer player) {
    ArrayList<ItemStack> hotbar = new ArrayList<>(9);
    for (int i = 0; i < 9; i++) {
      hotbar.add(player.getInventory().getItem(i));
    }
    return hotbar;
  }

  private static double potionAmplifier(LocalPlayer player) {
    double speed = 1;
    if (player.hasEffect(MobEffects.HASTE)) {
      speed *= 1 + (player.getEffect(MobEffects.HASTE).getAmplifier() + 1) * 0.2;
    }
    if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
      switch (player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
        case 0 :
          speed *= 0.3;
          break;
        case 1 :
          speed *= 0.09;
          break;
        case 2 :
          speed *= 0.0027; // you might think that 0.09*0.3 = 0.027 so that should be next, that would make too much sense. it's 0.0027.
          break;
        default :
          speed *= 0.00081;
          break;
      }
    }
    return speed;
  }
}
