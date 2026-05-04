package baritone.planning;

import java.util.HashMap;
import java.util.Map;

public record ResourceVector(Map<ResourceType, Long> quantities) {
  public static final ResourceVector ZERO = new ResourceVector(Map.of());

  public ResourceVector {
    quantities = Map.copyOf(quantities);
  }

  public static ResourceVector of(ResourceType type, long quantity) {
    return quantity == 0L ? ZERO : new ResourceVector(Map.of(type, quantity));
  }

  public long quantity(ResourceType type) {
    return quantities.getOrDefault(type, 0L);
  }

  public ResourceVector plus(ResourceVector rhs) {
    HashMap<ResourceType, Long> out = new HashMap<>(quantities);
    rhs.quantities.forEach((type, quantity) -> out.merge(type, quantity, Long::sum));
    out.entrySet().removeIf(entry -> entry.getValue() == 0L);
    return out.isEmpty() ? ZERO : new ResourceVector(out);
  }

  public ResourceVector minus(ResourceVector rhs) {
    HashMap<ResourceType, Long> out = new HashMap<>(quantities);
    rhs.quantities.forEach((type, quantity) -> out.merge(type, -quantity, Long::sum));
    out.entrySet().removeIf(entry -> entry.getValue() == 0L);
    return out.isEmpty() ? ZERO : new ResourceVector(out);
  }

  public boolean dominates(ResourceVector rhs) {
    for (Map.Entry<ResourceType, Long> entry : rhs.quantities.entrySet()) {
      if (quantity(entry.getKey()) < entry.getValue()) {
        return false;
      }
    }
    return true;
  }
}
