package baritone.api;

import baritone.api.utils.SettingsUtil;
import baritone.api.utils.GoldenTuning;

/**
 * Exposes the {@link IBaritoneProvider} instance and the {@link Settings} instance for API usage.
 *
 * @author Brady
 * @since 9/23/2018
 */
public final class BaritoneAPI {

  private static final IBaritoneProvider provider;
  private static final Settings settings;

  static {
    settings = new Settings();
    GoldenTuning.reloadConfigured();
    GoldenTuning.applyCurrent(settings);
    if (clientEnvironment()) {
      SettingsUtil.readAndApply(settings, SettingsUtil.SETTINGS_DEFAULT_NAME);
      try {
        provider = (IBaritoneProvider) Class.forName("baritone.BaritoneProvider").newInstance();
      } catch (ReflectiveOperationException ex) {
        throw new RuntimeException(ex);
      }
    } else {
      provider = null;
    }
  }

  public static IBaritoneProvider getProvider() { return BaritoneAPI.provider; }

  public static Settings getSettings() { return BaritoneAPI.settings; }

  private static boolean clientEnvironment() {
    try {
      Class<?> loaderType = Class.forName("net.fabricmc.loader.api.FabricLoader");
      Object loader = loaderType.getMethod("getInstance").invoke(null);
      Object environment = loaderType.getMethod("getEnvironmentType").invoke(loader);
      return "CLIENT".equals(String.valueOf(environment));
    } catch (ReflectiveOperationException | LinkageError ignored) {
      return true;
    }
  }
}
