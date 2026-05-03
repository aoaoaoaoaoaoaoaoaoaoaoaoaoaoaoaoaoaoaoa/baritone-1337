package baritone.integration.xaero;

import baritone.api.utils.IPlayerContext;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

public final class XaeroWaypointStore {
  private final IPlayerContext ctx;

  public XaeroWaypointStore(IPlayerContext ctx) {
    this.ctx = ctx;
  }

  public List<XaeroWaypoint> readCurrentDimension() {
    return read(false);
  }

  public List<XaeroWaypoint> readAllDimensions() {
    return read(true);
  }

  public String currentDimensionDirectory() {
    ResourceKey<Level> key = ctx.world().dimension();
    if (Level.OVERWORLD.equals(key)) {
      return "dim%0";
    }
    if (Level.NETHER.equals(key)) {
      return "dim%-1";
    }
    if (Level.END.equals(key)) {
      return "dim%1";
    }
    Identifier id = key.identifier();
    return "dim%" + id.getNamespace() + "$" + replaceTrailingDots(id.getPath().replace('/', '%'), ',');
  }

  private List<XaeroWaypoint> read(boolean allDimensions) {
    String currentDimension = currentDimensionDirectory();
    return waypointFiles(allDimensions, currentDimension).flatMap(this::readFile).sorted(order(currentDimension)).toList();
  }

  private Stream<Path> waypointFiles(boolean allDimensions, String currentDimension) {
    return worldDirectories().stream().flatMap(world -> {
      if (allDimensions) {
        try {
          try (Stream<Path> children = Files.list(world)) {
            return children.filter(Files::isDirectory).map(dir -> dir.resolve("waypoints.txt")).filter(Files::isRegularFile).toList().stream();
          }
        } catch (IOException ignored) {
          return Stream.empty();
        }
      }
      Path file = world.resolve(currentDimension).resolve("waypoints.txt");
      return Files.isRegularFile(file) ? Stream.of(file) : Stream.empty();
    });
  }

  private List<Path> worldDirectories() {
    Path root = ctx.minecraft().gameDirectory.toPath().resolve("xaero").resolve("minimap");
    Set<Path> candidates = new LinkedHashSet<>();
    currentWorldDirectoryName().ifPresent(name -> candidates.add(root.resolve(name)));
    if (candidates.stream().noneMatch(Files::isDirectory)) {
      try (Stream<Path> children = Files.list(root)) {
        children.filter(Files::isDirectory).sorted(Comparator.comparingLong(XaeroWaypointStore::mtime).reversed()).forEach(candidates::add);
      } catch (IOException ignored) {
      }
    }
    return candidates.stream().filter(Files::isDirectory).toList();
  }

  private Optional<String> currentWorldDirectoryName() {
    if (ctx.minecraft().hasSingleplayerServer()) {
      Path worldDir = ctx.minecraft().getSingleplayerServer().getWorldPath(LevelResource.ROOT);
      if (worldDir.getFileName() != null && (worldDir.getFileName().toString().startsWith("DIM") || worldDir.getFileName().toString().equals("data"))) {
        worldDir = worldDir.getParent();
      }
      return worldDir == null || worldDir.getFileName() == null ? Optional.empty() : Optional.of(worldDir.getFileName().toString());
    }
    ServerData server = ctx.minecraft().getCurrentServer();
    if (server == null) {
      return Optional.empty();
    }
    String ip = server.isRealm() ? "realms" : server.ip;
    if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")) {
      ip = ip.replace(':', '_');
    }
    return Optional.of(ip);
  }

  private Stream<XaeroWaypoint> readFile(Path file) {
    List<XaeroWaypoint> waypoints = new ArrayList<>();
    try {
      List<String> lines = Files.readAllLines(file);
      for (int i = 0; i < lines.size(); i++) {
        parse(file, i + 1, lines.get(i)).ifPresent(waypoints::add);
      }
    } catch (IOException ignored) {
    }
    return waypoints.stream();
  }

  private Optional<XaeroWaypoint> parse(Path file, int lineNumber, String line) {
    if (line.isBlank() || line.charAt(0) == '#') {
      return Optional.empty();
    }
    String[] parts = line.split(":", -1);
    if (parts.length < 10 || !"waypoint".equalsIgnoreCase(parts[0])) {
      return Optional.empty();
    }
    try {
      String yRaw = parts[4];
      OptionalInt y = "~".equals(yRaw) ? OptionalInt.empty() : OptionalInt.of(Integer.parseInt(yRaw));
      String dimension = file.getParent().getFileName().toString();
      String world = file.getParent().getParent().getFileName().toString();
      return Optional.of(new XaeroWaypoint(id(file, lineNumber, line), world, dimension, decode(parts[1]), decode(parts[2]), Integer.parseInt(parts[3]), y, Integer.parseInt(parts[5]),
        Integer.parseInt(parts[6]), Boolean.parseBoolean(parts[7]), Integer.parseInt(parts[8]), decode(parts[9]), file, lineNumber));
    } catch (RuntimeException ignored) {
      return Optional.empty();
    }
  }

  private static Comparator<XaeroWaypoint> order(String currentDimension) {
    return Comparator.comparing((XaeroWaypoint wp) -> !wp.sameDimension(currentDimension)).thenComparing(XaeroWaypoint::disabled).thenComparing(wp -> wp.name().toLowerCase(Locale.ROOT))
      .thenComparing(XaeroWaypoint::world).thenComparing(XaeroWaypoint::dimensionDirectory).thenComparingInt(XaeroWaypoint::line);
  }

  private static String decode(String value) {
    return value.replace("§§", ":");
  }

  private static String replaceTrailingDots(String value, char replacement) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '.') {
      end--;
    }
    return end == value.length() ? value : value.substring(0, end) + String.valueOf(replacement).repeat(value.length() - end);
  }

  private static long mtime(Path path) {
    try {
      return Files.getLastModifiedTime(path).toMillis();
    } catch (IOException ignored) {
      return Long.MIN_VALUE;
    }
  }

  private static String id(Path file, int lineNumber, String line) {
    long hash = 0xcbf29ce484222325L;
    String input = file.toAbsolutePath().normalize() + ":" + lineNumber + ":" + line;
    for (int i = 0; i < input.length(); i++) {
      hash ^= input.charAt(i);
      hash *= 0x100000001b3L;
    }
    return Long.toUnsignedString(hash, 36);
  }
}
