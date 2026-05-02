package baritone.gradle.task;

import org.gradle.api.tasks.TaskAction;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.stream.Collectors;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

/**
 * @author Brady
 * @since 10/12/2018
 */
public class CreateDistTask extends BaritoneGradleTask {

  private static MessageDigest SHA1_DIGEST;

  @TaskAction
  protected void exec() throws Exception {
    super.doFirst();
    super.verifyArtifacts();

    // Define the distribution file paths
    Path api = getRootRelativeFile("dist/" + getFileName(artifactApiPath));
    Path standalone = getRootRelativeFile("dist/" + getFileName(artifactStandalonePath));
    Path unoptimized = getRootRelativeFile("dist/" + getFileName(artifactUnoptimizedPath));

    Path dir = getRootRelativeFile("dist/");
    Files.createDirectories(dir);
    try (var entries = Files.list(dir)) {
      for (Path stale : entries.filter(e -> e.getFileName().toString().endsWith(".jar")).toList()) {
        Files.delete(stale);
      }
    }

    Files.copy(this.artifactApiPath, api, REPLACE_EXISTING);
    Files.copy(this.artifactStandalonePath, standalone, REPLACE_EXISTING);
    Files.copy(this.artifactUnoptimizedPath, unoptimized, REPLACE_EXISTING);

    List<String> shasum =
      Files.list(getRootRelativeFile("dist/")).filter(e -> e.getFileName().toString().endsWith(".jar")).map(path -> sha1(path) + "  " + path.getFileName().toString()).collect(Collectors.toList());

    shasum.forEach(System.out::println);

    Files.write(getRootRelativeFile("dist/checksums.txt"), shasum);
  }

  private static String getFileName(Path p) {
    return p.getFileName().toString();
  }

  private static synchronized String sha1(Path path) {
    try {
      if (SHA1_DIGEST == null) {
        SHA1_DIGEST = MessageDigest.getInstance("SHA-1");
      }
      return bytesToHex(SHA1_DIGEST.digest(Files.readAllBytes(path))).toLowerCase();
    } catch (Exception e) {
      // haha no thanks
      throw new IllegalStateException(e);
    }
  }

  private static final byte[] HEX_ARRAY = "0123456789ABCDEF".getBytes(StandardCharsets.US_ASCII);

  public static String bytesToHex(byte[] bytes) {
    byte[] hexChars = new byte[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = HEX_ARRAY[v >>> 4];
      hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
    }
    return new String(hexChars, StandardCharsets.UTF_8);
  }
}
