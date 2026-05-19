package baritone.profile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class AsyncProfilerController {
  private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
  private static final long COMMAND_TIMEOUT_SECONDS = 15L;
  private static final String DEFAULT_EVENT = "cpu";
  private static final String DEFAULT_FORMAT = "html";

  private final Path outputDirectory;
  private Session session;
  private Path lastOutput;
  private String lastLog = "";

  public AsyncProfilerController(Path outputDirectory) {
    this.outputDirectory = outputDirectory;
  }

  public synchronized String start(String rawEvent) {
    String event = event(rawEvent);
    if (session != null) {
      return "async-profiler already running: " + session.event() + " since " + session.startedAt();
    }
    try {
      Files.createDirectories(outputDirectory);
      CommandResult result = run(startCommand(event));
      if (!result.success()) {
        return "async-profiler failed to start: " + result.summary();
      }
      session = new Session(event, Instant.now());
      lastLog = result.output();
      return "async-profiler started (" + event + ") for pid " + pid();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      return "async-profiler failed to start: " + e;
    }
  }

  public synchronized String stop(String rawFormat) {
    if (session == null) {
      return "async-profiler is not running";
    }
    String format = format(rawFormat);
    Path output = outputPath(session.event(), format);
    try {
      CommandResult result = run(outputCommand("stop", format, output));
      if (!result.success()) {
        return "async-profiler failed to stop: " + result.summary();
      }
      session = null;
      lastOutput = output;
      lastLog = result.output();
      return "async-profiler stopped: " + output.toAbsolutePath();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      return "async-profiler failed to stop: " + e;
    }
  }

  public synchronized String dump(String rawFormat) {
    if (session == null) {
      return "async-profiler is not running";
    }
    String format = format(rawFormat);
    Path output = outputPath(session.event(), format);
    try {
      CommandResult result = run(outputCommand("dump", format, output));
      if (!result.success()) {
        return "async-profiler failed to dump: " + result.summary();
      }
      lastOutput = output;
      lastLog = result.output();
      return "async-profiler dump: " + output.toAbsolutePath();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      return "async-profiler failed to dump: " + e;
    }
  }

  public synchronized String status() {
    String local = session == null ? "async-profiler idle" : "async-profiler running: " + session.event() + " since " + session.startedAt();
    try {
      CommandResult result = run(List.of(binary(), "status", Long.toString(pid())));
      lastLog = result.output();
      return local + "\n" + result.output().strip();
    } catch (IOException | InterruptedException e) {
      Thread.currentThread().interrupt();
      return local + "\nstatus command failed: " + e;
    }
  }

  public synchronized String last() {
    if (lastOutput != null) {
      return "Last async profile: " + lastOutput.toAbsolutePath();
    }
    return lastLog.isBlank() ? "No async profile has been saved yet" : "No async profile saved yet; last profiler output:\n" + lastLog.strip();
  }

  private static long pid() {
    return ProcessHandle.current().pid();
  }

  private List<String> startCommand(String event) {
    ArrayList<String> command = new ArrayList<>();
    command.add(binary());
    command.add("start");
    command.add("-e");
    command.add(event);
    command.add("-t");
    command.add("-j");
    command.add("256");
    switch (event) {
      case "alloc" -> {
        command.add("--alloc");
        command.add("1m");
      }
      case "wall" -> {
        command.add("--wall");
        command.add("10ms");
      }
      default -> {
      }
    }
    command.add(Long.toString(pid()));
    return command;
  }

  private List<String> outputCommand(String action, String format, Path output) {
    return List.of(binary(), action, "-o", asprofFormat(format), "-f", output.toAbsolutePath().toString(), Long.toString(pid()));
  }

  private Path outputPath(String event, String format) {
    String extension = switch (format) {
      case "html" -> "html";
      case "jfr" -> "jfr";
      case "collapsed" -> "collapsed";
      case "flat" -> "txt";
      case "tree" -> "txt";
      default -> format;
    };
    return outputDirectory.resolve("async-" + FILE_TIME.format(Instant.now()) + "-" + event + "." + extension);
  }

  private static String event(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_EVENT;
    }
    String event = raw.toLowerCase(Locale.ROOT);
    return switch (event) {
      case "cpu", "wall", "alloc", "lock", "nativemem", "cache-misses", "itimer" -> event;
      default -> DEFAULT_EVENT;
    };
  }

  private static String format(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT_FORMAT;
    }
    String format = raw.toLowerCase(Locale.ROOT);
    return switch (format) {
      case "html", "flamegraph", "jfr", "collapsed", "flat", "tree" -> format.equals("flamegraph") ? "html" : format;
      default -> DEFAULT_FORMAT;
    };
  }

  private static String asprofFormat(String format) {
    return format.equals("html") ? "flamegraph" : format;
  }

  private static String binary() {
    String configured = Optional.ofNullable(System.getProperty("baritone.asprof")).filter(s -> !s.isBlank()).orElseGet(() -> Optional.ofNullable(System.getenv("BARITONE_ASPROF")).orElse(""));
    if (!configured.isBlank()) {
      return configured;
    }
    for (String candidate : List.of("/usr/bin/asprof", "/usr/local/bin/asprof", "asprof")) {
      if (candidate.indexOf('/') < 0 || Files.isExecutable(Path.of(candidate))) {
        return candidate;
      }
    }
    return "asprof";
  }

  private static CommandResult run(List<String> command) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      return new CommandResult(-1, "$ " + String.join(" ", command) + "\nTimed out after " + COMMAND_TIMEOUT_SECONDS + "s");
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new CommandResult(process.exitValue(), output.isBlank() ? "$ " + String.join(" ", command) : output);
  }

  private record Session(String event, Instant startedAt) {
  }

  private record CommandResult(int exitCode, String output) {
    boolean success() {
      return exitCode == 0;
    }

    String summary() {
      return output.strip().isBlank() ? "exit " + exitCode : output.strip();
    }
  }
}
