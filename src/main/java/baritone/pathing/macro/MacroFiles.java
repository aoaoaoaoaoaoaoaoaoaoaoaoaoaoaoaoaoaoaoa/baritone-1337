package baritone.pathing.macro;

import java.nio.file.Files;
import java.nio.file.Path;

public final class MacroFiles {
  private MacroFiles() {
  }

  public static Path resolve(String raw) {
    Path path = Path.of(raw);
    if (path.isAbsolute()) {
      return path;
    }
    for (Path base = Path.of(System.getProperty("user.dir")); base != null; base = base.getParent()) {
      Path resolved = base.resolve(path).normalize();
      if (Files.exists(resolved)) {
        return resolved;
      }
    }
    return Path.of(System.getProperty("user.dir")).resolve(path).normalize();
  }
}
