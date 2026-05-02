package baritone.process.elytra;

import baritone.api.utils.Rotation;
import net.minecraft.world.phys.Vec3;

record ElytraSolution(ElytraSolverContext context, Rotation rotation, Vec3 goingTo, boolean solvedPitch, boolean forceUseFirework) {}
