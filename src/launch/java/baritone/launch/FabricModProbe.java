package baritone.launch;

final class FabricModProbe {

  private FabricModProbe() {
  }

  static boolean loaded(String modId) {
    try {
      Class<?> loaderType = Class.forName("net.fabricmc.loader.api.FabricLoader");
      Object loader = loaderType.getMethod("getInstance").invoke(null);
      return (Boolean) loaderType.getMethod("isModLoaded", String.class).invoke(loader, modId);
    } catch (ReflectiveOperationException | LinkageError ignored) {
      return false;
    }
  }
}
