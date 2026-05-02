package baritone.config;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.utils.GeofenceBox;
import baritone.geofence.GeofenceSettings;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class GeofenceConfigScreen extends Screen {
  private static final int ROWS = 6;
  private static final int ROW_HEIGHT = 25;

  private final Screen parent;
  private EditBox manual;
  private int page;

  public GeofenceConfigScreen(Screen parent) {
    super(Component.literal("modification geofences"));
    this.parent = parent;
  }

  public static void open(Screen parent) {
    Minecraft.getInstance().setScreen(new GeofenceConfigScreen(parent));
  }

  @Override
  protected void init() {
    List<GeofenceBox> boxes = GeofenceSettings.boxes();
    int pages = Math.max(1, (boxes.size() + ROWS - 1) / ROWS);
    page = Math.max(0, Math.min(page, pages - 1));

    int center = width / 2;
    addRenderableWidget(new StringWidget(width, 20, title, font));
    addRenderableWidget(new StringWidget(width, 34, Component.literal(GeofenceSettings.currentDimension()).withStyle(ChatFormatting.GRAY), font));

    manual = new EditBox(font, center - 190, 56, 270, 20, Component.literal("x/y/z..x/y/z"));
    manual.setMaxLength(128);
    manual.setTooltip(Tooltip.create(Component.literal("x/y/z..x/y/z; current dimension is used unless a dimension@ prefix is supplied")));
    addRenderableWidget(manual);
    addRenderableWidget(Button.builder(Component.literal("Add"), b -> addManual()).bounds(center + 90, 56, 50, 20).build());
    addRenderableWidget(Button.builder(Component.literal("Add Selection"), b -> addSelections()).bounds(center + 150, 56, 110, 20).build()).active = selectionCount() > 0;

    int from = page * ROWS;
    int to = Math.min(boxes.size(), from + ROWS);
    for (int i = from; i < to; i++) {
      addRow(i, boxes.get(i), center, 90 + (i - from) * ROW_HEIGHT);
    }
    if (boxes.isEmpty()) {
      addRenderableWidget(new StringWidget(center - 120, 108, 240, 20, Component.literal("No protected modification boxes.").withStyle(ChatFormatting.GRAY), font));
    }

    int bottom = height - 28;
    addRenderableWidget(Button.builder(Component.literal("< page"), b -> switchPage(-1)).bounds(center - 190, bottom, 80, 20).build()).active = page > 0;
    addRenderableWidget(new StringWidget(center - 100, bottom, 200, 20, Component.literal("page " + (page + 1) + "/" + pages), font));
    addRenderableWidget(Button.builder(Component.literal("page >"), b -> switchPage(1)).bounds(center + 110, bottom, 80, 20).build()).active = page + 1 < pages;
    addRenderableWidget(Button.builder(Component.literal("Clear"), b -> {
      GeofenceSettings.clear();
      rebuildWidgets();
    }).bounds(center - 55, bottom, 50, 20).build()).active = !boxes.isEmpty();
    addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(center + 5, bottom, 80, 20).build());
  }

  private void addRow(int index, GeofenceBox box, int center, int y) {
    String text = (index + 1) + ". " + box.describe();
    StringWidget label = new StringWidget(center - 260, y, 420, 20, Component.literal(text), font);
    label.setTooltip(Tooltip.create(Component.literal(box.serialized())));
    addRenderableWidget(label);
    addRenderableWidget(Button.builder(Component.literal("Remove"), b -> {
      GeofenceSettings.remove(index);
      rebuildWidgets();
    }).bounds(center + 170, y, 90, 20).build());
  }

  private void addManual() {
    try {
      GeofenceSettings.add(GeofenceBox.parse(manual.getValue(), GeofenceSettings.currentDimension()));
      rebuildWidgets();
    } catch (RuntimeException ignored) {
      manual.setTextColor(0xFF5555);
    }
  }

  private void addSelections() {
    GeofenceSettings.addSelections(baritone().getSelectionManager().getSelections());
    rebuildWidgets();
  }

  private int selectionCount() {
    return baritone().getSelectionManager().getSelections().length;
  }

  private static IBaritone baritone() {
    return BaritoneAPI.getProvider().getPrimaryBaritone();
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
