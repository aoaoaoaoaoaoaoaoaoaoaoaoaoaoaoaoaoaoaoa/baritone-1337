package baritone.launch.mixins;

import baritone.api.utils.accessor.IItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class MixinItemStack implements IItemStack {

    @Unique
    private int baritoneHash;

    @Shadow
    public abstract Item getItem();

    @Shadow
    public abstract int getDamageValue();

    private void recalculateHash() {
        Item item = getItem();
        baritoneHash = item == null ? -1 : item.hashCode() + getDamageValue();
    }

    @Inject(
            method = "setDamageValue",
            at = @At("TAIL")
    )
    private void onItemDamageSet(CallbackInfo ci) {
        recalculateHash();
    }

    @Override
    public int getBaritoneHash() {
        // cannot do this in an init mixin because silentlib likes creating new
        // items in getDamageValue, which we call in recalculateHash
        if (baritoneHash == 0) recalculateHash();
        return baritoneHash;
    }
}
