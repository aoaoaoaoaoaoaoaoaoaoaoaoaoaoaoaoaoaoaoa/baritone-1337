package baritone.config;

import baritone.Baritone;
import baritone.api.Settings;
import baritone.api.utils.SettingsUtil;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class BaritoneConfigScreen extends Screen {
  private static final int ROWS = 8;
  private static final int ROW_HEIGHT = 28;

  private final Screen parent;
  private final List<SettingsSurface.Category> categories = SettingsSurface.categories();
  private int categoryIndex;
  private int page;

  public BaritoneConfigScreen(Screen parent) {
    super(Component.literal("baritone-1337 config"));
    this.parent = parent;
  }

  public static void open(Screen parent) {
    Minecraft.getInstance().setScreen(new BaritoneConfigScreen(parent));
  }

  @Override
  protected void init() {
    SettingsSurface.Category category = categories.get(categoryIndex);
    int pages = Math.max(1, (category.entries().size() + ROWS - 1) / ROWS);
    page = Math.max(0, Math.min(page, pages - 1));

    addRenderableWidget(new StringWidget(width, 20, title, font));
    addRenderableWidget(Button.builder(Component.literal("Geofences"), b -> minecraft.setScreen(new GeofenceConfigScreen(this))).bounds(width - 110, 18, 100, 20).build());

    int top = 42;
    int center = width / 2;
    addRenderableWidget(Button.builder(Component.literal("<"), b -> switchCategory(-1)).bounds(center - 190, top, 28, 20).build());
    addRenderableWidget(new StringWidget(center - 155, top, 310, 20, Component.literal(category.title() + "  " + (categoryIndex + 1) + "/" + categories.size()), font));
    addRenderableWidget(Button.builder(Component.literal(">"), b -> switchCategory(1)).bounds(center + 162, top, 28, 20).build());

    int rowY = 74;
    int from = page * ROWS;
    int to = Math.min(category.entries().size(), from + ROWS);
    for (int i = from; i < to; i++) {
      addRow(category.entries().get(i), center, rowY + (i - from) * ROW_HEIGHT);
    }

    int bottom = height - 28;
    addRenderableWidget(Button.builder(Component.literal("< page"), b -> switchPage(-1)).bounds(center - 190, bottom, 80, 20).build()).active = page > 0;
    addRenderableWidget(new StringWidget(center - 100, bottom, 200, 20, Component.literal("page " + (page + 1) + "/" + pages), font));
    addRenderableWidget(Button.builder(Component.literal("page >"), b -> switchPage(1)).bounds(center + 110, bottom, 80, 20).build()).active = page + 1 < pages;
    addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(center - 40, bottom, 80, 20).build());
  }

  private void addRow(SettingsSurface.Entry<?> entry, int center, int y) {
    Settings.Setting<?> setting = entry.setting();
    StringWidget label = new StringWidget(center - 210, y, 150, 20, Component.literal(entry.title()), font);
    label.setTooltip(Tooltip.create(Component.literal(entry.description() + "\n" + setting.getName())));
    addRenderableWidget(label);

    if (setting.getValueClass() == Boolean.class) {
      @SuppressWarnings("unchecked")
      Settings.Setting<Boolean> bool = (Settings.Setting<Boolean>) setting;
      addRenderableWidget(Button.builder(boolMessage(bool.value), b -> {
        bool.value = !bool.value;
        SettingsUtil.save(Baritone.settings());
        rebuildWidgets();
      }).bounds(center - 50, y, 95, 20).tooltip(Tooltip.create(Component.literal(setting.getName()))).build());
    } else {
      EditBox box = new EditBox(font, center - 50, y, 155, 20, Component.literal(setting.getName()));
      box.setMaxLength(256);
      box.setValue(SettingsUtil.settingValueToString(setting));
      box.setTooltip(Tooltip.create(Component.literal(setting.getName())));
      addRenderableWidget(box);
      addRenderableWidget(Button.builder(Component.literal("Set"), b -> apply(setting, box)).bounds(center + 110, y, 45, 20).build());
    }

    addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
      setting.reset();
      SettingsUtil.save(Baritone.settings());
      rebuildWidgets();
    }).bounds(center + 160, y, 60, 20).build());
  }

  private static Component boolMessage(boolean value) {
    return Component.literal(value ? "ON" : "OFF").withStyle(value ? ChatFormatting.GREEN : ChatFormatting.RED);
  }

  private void apply(Settings.Setting<?> setting, EditBox box) {
    try {
      SettingsUtil.parseAndApply(Baritone.settings(), setting.getName().toLowerCase(Locale.US), box.getValue());
      SettingsUtil.save(Baritone.settings());
      box.setTextColor(0xE0E0E0);
      box.setValue(SettingsUtil.settingValueToString(setting));
    } catch (Throwable ignored) {
      box.setTextColor(0xFF5555);
    }
  }

  private void switchCategory(int delta) {
    categoryIndex = Math.floorMod(categoryIndex + delta, categories.size());
    page = 0;
    rebuildWidgets();
  }

  private void switchPage(int delta) {
    page += delta;
    rebuildWidgets();
  }

  @Override
  public void onClose() {
    minecraft.setScreen(parent);
  }
}
