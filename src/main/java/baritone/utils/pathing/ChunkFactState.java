package baritone.utils.pathing;

public enum ChunkFactState {
  LIVE, CACHED, ABSENT;

  public boolean live() {
    return this == LIVE;
  }

  public boolean cached() {
    return this == CACHED;
  }

  public boolean pathingData() {
    return this != ABSENT;
  }
}
