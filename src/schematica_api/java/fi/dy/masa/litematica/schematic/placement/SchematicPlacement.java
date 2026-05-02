package fi.dy.masa.litematica.schematic.placement;

import com.google.common.collect.ImmutableMap;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

public class SchematicPlacement {

    public String getName() {
        throw new LinkageError();
    }

    public BlockPos getOrigin() {
        throw new LinkageError();
    }

    public Rotation getRotation() {
        throw new LinkageError();
    }

    public Mirror getMirror() {
        throw new LinkageError();
    }

    public ImmutableMap<String, SubRegionPlacement> getEnabledRelativeSubRegionPlacements() {
        throw new LinkageError();
    }

    public LitematicaSchematic getSchematic() {
        throw new LinkageError();
    }
}