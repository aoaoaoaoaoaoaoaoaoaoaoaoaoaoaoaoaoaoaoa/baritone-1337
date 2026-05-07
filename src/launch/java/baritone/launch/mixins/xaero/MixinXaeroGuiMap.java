package baritone.launch.mixins.xaero;

import baritone.launch.xaero.BaritoneXaeroGuiMapAccess;
import baritone.launch.xaero.BaritoneXaeroPathHereOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.ArrayList;

@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
abstract class MixinXaeroGuiMap implements BaritoneXaeroGuiMapAccess {

  @Inject(method = "getRightClickOptions", at = @At("RETURN"), remap = false)
  private void addBaritonePathHere(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
    ArrayList<RightClickOption> options = cir.getReturnValue();
    options.add(new BaritoneXaeroPathHereOption(options.size(), (IRightClickableElement) (Object) this));
  }

  @Override
  @Accessor("rightClickX")
  public abstract int baritone$rightClickX();

  @Override
  @Accessor("rightClickY")
  public abstract int baritone$rightClickY();

  @Override
  @Accessor("rightClickZ")
  public abstract int baritone$rightClickZ();

  @Override
  @Accessor("rightClickDim")
  public abstract ResourceKey<Level> baritone$rightClickDim();
}
