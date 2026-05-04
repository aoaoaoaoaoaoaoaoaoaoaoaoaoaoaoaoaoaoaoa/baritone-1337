package baritone.planning;

public record ResourceDelta(ResourceVector consume, ResourceVector produce, ResourceVector reserve, ResourceVector release) {
  public static final ResourceDelta ZERO = new ResourceDelta(ResourceVector.ZERO, ResourceVector.ZERO, ResourceVector.ZERO, ResourceVector.ZERO);
}
