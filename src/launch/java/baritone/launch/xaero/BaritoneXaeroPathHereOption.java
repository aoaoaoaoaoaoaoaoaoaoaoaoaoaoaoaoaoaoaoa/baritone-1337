package baritone.launch.xaero;

import baritone.integration.xaero.XaeroPathingBridge;
import xaero.map.gui.GuiMap;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import net.minecraft.client.gui.screens.Screen;

public final class BaritoneXaeroPathHereOption extends RightClickOption {

  private static final String KEY = "gui.baritone_xaero_path_here";

  public BaritoneXaeroPathHereOption(int index, IRightClickableElement owner) {
    super(KEY, index, owner);
  }

  @Override
  public void onAction(Screen screen) {
    if (screen instanceof BaritoneXaeroGuiMapAccess map) {
      XaeroPathingBridge.pathHere(map.baritone$rightClickX(), map.baritone$rightClickY(), map.baritone$rightClickZ(), map.baritone$rightClickDim());
    }
    if (screen instanceof GuiMap map) {
      map.closeRightClick();
    }
  }
}
