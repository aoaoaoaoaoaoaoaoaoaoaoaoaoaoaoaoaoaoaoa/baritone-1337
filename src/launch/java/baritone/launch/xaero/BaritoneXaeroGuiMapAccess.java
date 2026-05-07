package baritone.launch.xaero;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public interface BaritoneXaeroGuiMapAccess {

  int baritone$rightClickX();

  int baritone$rightClickY();

  int baritone$rightClickZ();

  ResourceKey<Level> baritone$rightClickDim();
}
