package baritone.api;

import baritone.api.utils.SettingsUtil;

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
        SettingsUtil.readAndApply(settings, SettingsUtil.SETTINGS_DEFAULT_NAME);

        try {
            provider = (IBaritoneProvider) Class.forName("baritone.BaritoneProvider").newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    public static IBaritoneProvider getProvider() {
        return BaritoneAPI.provider;
    }

    public static Settings getSettings() {
        return BaritoneAPI.settings;
    }
}
