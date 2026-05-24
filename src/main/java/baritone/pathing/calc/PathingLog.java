package baritone.pathing.calc;

final class PathingLog {
  private PathingLog() {
  }

  static void debug(String message) {
    System.out.println("[Baritone] " + message);
  }

  static void direct(String message) {
    System.out.println("[Baritone] " + message);
  }

  static void notification(String message, boolean error) {
    (error ? System.err : System.out).println("[Baritone] " + message);
  }
}
