package baritone.transport;

import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;

public interface TransportPlanner<R extends TransportRoute> {
  TransportKind kind();

  CompletableFuture<R> plan(BlockPos src, BlockPos dst);
}
