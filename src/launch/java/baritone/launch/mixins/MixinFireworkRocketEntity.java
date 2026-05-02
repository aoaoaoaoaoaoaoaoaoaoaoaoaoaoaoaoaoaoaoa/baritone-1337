package baritone.launch.mixins;

import baritone.utils.accessor.IFireworkRocketEntity;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.OptionalInt;

@Mixin(FireworkRocketEntity.class)
public abstract class MixinFireworkRocketEntity extends Entity implements IFireworkRocketEntity {

  @Shadow
  @Final
  private static EntityDataAccessor<OptionalInt> DATA_ATTACHED_TO_TARGET;

  @Shadow
  private LivingEntity attachedToEntity;

  @Shadow
  public abstract boolean isAttachedToEntity();

  private MixinFireworkRocketEntity(Level level) {
    super(EntityType.FIREWORK_ROCKET, level);
  }

  @Override
  public LivingEntity getBoostedEntity() {
    if (this.isAttachedToEntity() && this.attachedToEntity == null) { // isAttachedToEntity checks if the optional is present
      final Entity entity = this.level().getEntity(this.entityData.get(DATA_ATTACHED_TO_TARGET).getAsInt());
      if (entity instanceof LivingEntity) {
        this.attachedToEntity = (LivingEntity) entity;
      }
    }
    return this.attachedToEntity;
  }
}
