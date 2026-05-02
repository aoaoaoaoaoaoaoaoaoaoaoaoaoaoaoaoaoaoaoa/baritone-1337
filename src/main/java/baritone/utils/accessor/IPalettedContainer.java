package baritone.utils.accessor;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;

public interface IPalettedContainer<T> {

  Palette<T> getPalette();

  BitStorage getStorage();

  public interface IData<T> {

    Palette<T> getPalette();

    BitStorage getStorage();
  }
}
