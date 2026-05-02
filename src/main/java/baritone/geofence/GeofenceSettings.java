package baritone.geofence;

import baritone.Baritone;
import baritone.api.BaritoneAPI;
import baritone.api.selection.ISelection;
import baritone.api.utils.GeofenceBox;
import baritone.api.utils.SettingsUtil;
import java.util.ArrayList;
import java.util.List;

public final class GeofenceSettings {
  private GeofenceSettings() {
  }

  public static List<GeofenceBox> boxes() {
    return Baritone.settings().modificationGeofences.value;
  }

  public static void add(GeofenceBox box) {
    List<GeofenceBox> next = new ArrayList<>(boxes());
    next.add(box);
    replace(next);
  }

  public static int addSelections(ISelection[] selections) {
    if (selections.length == 0) {
      return 0;
    }
    List<GeofenceBox> next = new ArrayList<>(boxes());
    String dimension = currentDimension();
    for (ISelection selection : selections) {
      next.add(GeofenceBox.between(dimension, selection.min(), selection.max()));
    }
    replace(next);
    return selections.length;
  }

  public static GeofenceBox remove(int index) {
    List<GeofenceBox> next = new ArrayList<>(boxes());
    GeofenceBox removed = next.remove(index);
    replace(next);
    return removed;
  }

  public static int clear() {
    int count = boxes().size();
    replace(List.of());
    return count;
  }

  public static void replace(List<GeofenceBox> boxes) {
    if (boxes.isEmpty()) {
      Baritone.settings().modificationGeofences.reset();
    } else {
      Baritone.settings().modificationGeofences.value = new ArrayList<>(boxes);
    }
    SettingsUtil.save(Baritone.settings());
  }

  public static String currentDimension() {
    return BaritoneAPI.getProvider().getPrimaryBaritone().getPlayerContext().world().dimension().identifier().toString();
  }
}
