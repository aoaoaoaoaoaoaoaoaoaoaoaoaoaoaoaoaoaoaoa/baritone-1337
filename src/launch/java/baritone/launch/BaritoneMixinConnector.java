package baritone.launch;

import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.connect.IMixinConnector;

public class BaritoneMixinConnector implements IMixinConnector {

  @Override
  public void connect() {
    Mixins.addConfiguration("mixins.baritone.json");
  }
}
