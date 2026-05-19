package baritone.pathing.meso;

public record MesoTaskFailure(String code, String detail) {
  public MesoTaskFailure {
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("meso task failure code must be nonblank");
    }
    detail = detail == null ? "" : detail;
  }
}
