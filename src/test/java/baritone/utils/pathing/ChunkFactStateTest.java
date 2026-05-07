package baritone.utils.pathing;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class ChunkFactStateTest {
  @Test
  public void factStatesSeparateLiveCacheAndAbsence() {
    assertTrue(ChunkFactState.LIVE.live());
    assertFalse(ChunkFactState.CACHED.live());
    assertTrue(ChunkFactState.CACHED.cached());
    assertFalse(ChunkFactState.ABSENT.cached());
    assertTrue(ChunkFactState.LIVE.pathingData());
    assertTrue(ChunkFactState.CACHED.pathingData());
    assertFalse(ChunkFactState.ABSENT.pathingData());
  }

  @Test
  public void legacyLoadedCacheVocabularyDoesNotRegrow() throws IOException {
    Path root = Path.of("src/main/java/baritone");
    try (var files = Files.walk(root)) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
        String text = Files.readString(file);
        assertFalse(file + " resurrected worldContainsLoadedChunk", text.contains("worldContainsLoadedChunk("));
        assertFalse(file + " resurrected bsi.isLoaded", text.contains("bsi.isLoaded("));
        assertFalse(file + " resurrected context.isLoaded", text.contains("context.isLoaded("));
        assertFalse(file + " resurrected calcContext.isLoaded", text.contains("calcContext.isLoaded("));
        assertFalse(file + " resurrected calculatedWhileLoaded", text.contains("calculatedWhileLoaded"));
      }
    }
  }
}
