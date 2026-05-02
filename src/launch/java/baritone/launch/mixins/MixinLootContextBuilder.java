package baritone.launch.mixins;

import baritone.api.utils.BlockOptionalMeta;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.loot.LootContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LootContext.Builder.class)
public abstract class MixinLootContextBuilder {

    @Shadow public abstract ServerLevel getLevel();

    @Redirect(method = "create", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;reloadableRegistries()Lnet/minecraft/server/ReloadableServerRegistries$Holder;"))
    private ReloadableServerRegistries.Holder create(MinecraftServer instance) {
        if (instance != null) {
            return instance.reloadableRegistries();
        }
        if (getLevel() instanceof BlockOptionalMeta.ServerLevelStub sls) {
            return sls.holder();
        }
        return null;
    }

}
