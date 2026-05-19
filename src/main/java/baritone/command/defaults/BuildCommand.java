package baritone.command.defaults;

import baritone.Baritone;
import baritone.api.IBaritone;
import baritone.api.command.Command;
import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.datatypes.RelativeBlockPos;
import baritone.api.command.datatypes.RelativeFile;
import baritone.api.command.exception.CommandException;
import baritone.api.command.exception.CommandInvalidStateException;
import baritone.api.utils.BetterBlockPos;
import baritone.utils.schematic.SchematicSystem;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;

public class BuildCommand extends Command {

  private final File schematicsDir;

  public BuildCommand(IBaritone baritone) {
    super(baritone, "build");
    this.schematicsDir = new File(baritone.getPlayerContext().minecraft().gameDirectory, "schematics");
  }

  @Override
  public void execute(String label, IArgConsumer args) throws CommandException {
    final File file0 = args.getDatatypePost(RelativeFile.INSTANCE, schematicsDir).getAbsoluteFile();
    File file = file0;
    if (FilenameUtils.getExtension(file.getAbsolutePath()).isEmpty()) {
      file = new File(file.getAbsolutePath() + "." + Baritone.settings().schematicFallbackExtension.value);
    }
    if (!file.exists()) {
      if (file0.exists()) {
        throw new CommandInvalidStateException("Cannot load %s because the schematic format is unknown. Rename the file with the correct extension.".formatted(file));
      }
      throw new CommandInvalidStateException("Cannot find " + file);
    }
    if (SchematicSystem.INSTANCE.getByFile(file).isEmpty()) {
      StringJoiner formats = new StringJoiner(", ");
      SchematicSystem.INSTANCE.getFileExtensions().forEach(formats::add);
      throw new CommandInvalidStateException("Unsupported schematic format. Recognized file extensions are: %s".formatted(formats));
    }
    BetterBlockPos origin = ctx.playerFeet();
    BetterBlockPos buildOrigin;
    if (args.hasAny()) {
      args.requireMax(3);
      buildOrigin = args.getDatatypePost(RelativeBlockPos.INSTANCE, origin);
    } else {
      args.requireMax(0);
      buildOrigin = origin;
    }
    boolean success = baritone.getBuilderProcess().build(file.getName(), file, buildOrigin);
    if (!success) {
      throw new CommandInvalidStateException("Couldn't load the schematic. Either your schematic is corrupt or this is a bug.");
    }
    logDirect("Successfully loaded schematic for building\nOrigin: %s".formatted(buildOrigin));
  }

  @Override
  public Stream<String> tabComplete(String label, IArgConsumer args) throws CommandException {
    if (args.hasExactlyOne()) {
      return RelativeFile.tabComplete(args, schematicsDir);
    } else if (args.has(2)) {
      args.get();
      return args.tabCompleteDatatype(RelativeBlockPos.INSTANCE);
    }
    return Stream.empty();
  }

  @Override
  public String getShortDesc() { return "Build a schematic"; }

  @Override
  public List<String> getLongDesc() {
    return List.of("Build a schematic from a file.", "", "Usage:", "> build <filename> - Loads and builds '<filename>.schematic'", "> build <filename> <x> <y> <z> - Custom position");
  }
}
