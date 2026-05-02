package fi.dy.masa.litematica.world;

import net.minecraft.world.level.Level;

public abstract class WorldSchematic extends Level {
    private WorldSchematic() {
        super(null, null, null, null, false, false, 0, 0);
        throw new LinkageError();
    }
}
