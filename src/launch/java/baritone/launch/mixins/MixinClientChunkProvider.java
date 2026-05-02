package baritone.launch.mixins;

import baritone.utils.accessor.IChunkArray;
import baritone.utils.accessor.IClientChunkProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.lang.reflect.Field;
import java.util.Arrays;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkProvider implements IClientChunkProvider {

  @Final
  @Shadow
  ClientLevel level;

  @Override
  public ClientChunkCache createThreadSafeCopy() {
    IChunkArray arr = extractReferenceArray();
    ClientChunkCache result = new ClientChunkCache(level, arr.viewDistance() - 3); // -3 because its adds 3 for no reason lmao
    IChunkArray copyArr = ((IClientChunkProvider) result).extractReferenceArray();
    copyArr.copyFrom(arr);
    if (copyArr.viewDistance() != arr.viewDistance()) {
      throw new IllegalStateException(copyArr.viewDistance() + " " + arr.viewDistance());
    }
    return result;
  }

  @Override
  public IChunkArray extractReferenceArray() {
    for (Field f : ClientChunkCache.class.getDeclaredFields()) {
      if (IChunkArray.class.isAssignableFrom(f.getType())) {
        try {
          return (IChunkArray) f.get(this);
        } catch (IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }
    }
    throw new RuntimeException(Arrays.toString(ClientChunkCache.class.getDeclaredFields()));
  }
}
