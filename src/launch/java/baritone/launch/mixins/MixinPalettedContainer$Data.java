package baritone.launch.mixins;

import baritone.utils.accessor.IPalettedContainer.IData;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net/minecraft/world/level/chunk/PalettedContainer$Data")
public abstract class MixinPalettedContainer$Data<T> implements IData<T> {

    @Accessor
    public abstract Palette<T> getPalette();

    @Accessor
    public abstract BitStorage getStorage();
}
