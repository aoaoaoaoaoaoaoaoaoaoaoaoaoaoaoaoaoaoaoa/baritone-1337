package baritone.pathing.mounted;

import baritone.pathing.calc.BlockKey;
import baritone.pathing.movement.CalculationContext;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class HorseCollisionOracle {
  private static final double EPSILON = 1.0E-7D;
  private static final double GROUND_PROBE = 0.08D;
  private static final double MOUNTED_PLAYER_EYE_Y = 0.84375D + 1.62D;
  private static final double RIDER_SUFFOCATION_CERTIFICATION_WIDTH = 0.6D * 0.8D;
  private static final double PLAYER_SUFFOCATION_BOX_HEIGHT = 1.0E-6D;
  private static final int WATER_FLOAT_SUPPORT_DEPTH_BLOCKS = 1;
  private static final byte FAST_AIR = 1;
  private static final byte FAST_LIQUID = 2;
  private static final byte FAST_FULL = 3;
  private static final byte FAST_BOTTOM_SLAB = 4;
  private static final byte FAST_TOP_SLAB = 5;
  private static final byte FAST_HAZARD_FULL = 6;
  private static final byte FAST_EXACT = 7;
  private static final byte FAST_GROUNDED_UNKNOWN = 0;
  private static final byte FAST_GROUNDED_FALSE = 1;
  private static final byte FAST_GROUNDED_TRUE = 2;
  private static final VoxelShape BOTTOM_SLAB_SHAPE = Shapes.box(0D, 0D, 0D, 1D, 0.5D, 1D);
  private static final VoxelShape TOP_SLAB_SHAPE = Shapes.box(0D, 0.5D, 0D, 1D, 1D, 1D);

  private final Blocks blocks;
  private final double halfWidth;
  private final double height;
  private final double stepHeight;
  private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
  private final ArrayList<VoxelShape> colliders = new ArrayList<>(32);
  private final ArrayList<VoxelShape> stepColliders = new ArrayList<>(32);
  private final float[] stepHeights = new float[16];
  private final Long2ByteOpenHashMap waterCache = binaryCache();
  private final Long2ByteOpenHashMap surfaceWaterCache = binaryCache();
  private final Long2ByteOpenHashMap fastShapeCache = binaryCache();

  private HorseCollisionOracle(Blocks blocks, double halfWidth, double height, double stepHeight) {
    this.blocks = blocks;
    this.halfWidth = halfWidth;
    this.height = height;
    this.stepHeight = stepHeight;
  }

  private static Long2ByteOpenHashMap binaryCache() {
    Long2ByteOpenHashMap cache = new Long2ByteOpenHashMap();
    cache.defaultReturnValue((byte) 0);
    return cache;
  }

  static HorseCollisionOracle calculation(CalculationContext context, double halfWidth, double height, double stepHeight) {
    return new HorseCollisionOracle(new CalculationBlocks(context), halfWidth, height, stepHeight);
  }

  public static HorseCollisionOracle live(Level world, double halfWidth, double height, double stepHeight) {
    return new HorseCollisionOracle(new LiveBlocks(world), halfWidth, height, stepHeight);
  }

  AABB box(double centerX, double feetY, double centerZ) {
    return new AABB(centerX - halfWidth, feetY, centerZ - halfWidth, centerX + halfWidth, feetY + height, centerZ + halfWidth);
  }

  boolean clearPose(double centerX, double feetY, double centerZ) {
    return !intersects(box(centerX, feetY, centerZ)) && !riderSuffocating(centerX, feetY, centerZ);
  }

  public boolean standable(double centerX, int feetY, double centerZ) {
    if (!clearPose(centerX, feetY, centerZ)) {
      return false;
    }
    if (waterSupport(centerX, feetY, centerZ)) {
      return true;
    }
    return !hullTouchesWater(centerX, feetY, centerZ) && grounded(centerX, feetY, centerZ);
  }

  public boolean balancedStandable(double centerX, int feetY, double centerZ) {
    if (!clearPose(centerX, feetY, centerZ)) {
      return false;
    }
    if (balancedWaterSupport(centerX, feetY, centerZ)) {
      return true;
    }
    return !hullTouchesWater(centerX, feetY, centerZ) && balancedGrounded(centerX, feetY, centerZ);
  }

  public boolean centerStandable(double centerX, int feetY, double centerZ) {
    if (!clearPose(centerX, feetY, centerZ)) {
      return false;
    }
    if (centerWaterSupport(centerX, feetY, centerZ)) {
      return true;
    }
    return !hullTouchesWater(centerX, feetY, centerZ) && centerGrounded(centerX, feetY, centerZ);
  }

  boolean grounded(double centerX, double feetY, double centerZ) {
    AABB box = box(centerX, feetY, centerZ);
    byte fast = fastGrounded(box.minX, box.minY - GROUND_PROBE, box.minZ, box.maxX, box.minY + Math.min(0.16D, height), box.maxZ, feetY);
    if (fast != FAST_GROUNDED_UNKNOWN) {
      return fast == FAST_GROUNDED_TRUE;
    }
    collect(box.expandTowards(0D, -GROUND_PROBE, 0D), colliders);
    if (colliders.isEmpty()) {
      return false;
    }
    return collide(Direction.Axis.Y, box, colliders, -GROUND_PROBE) > -GROUND_PROBE + EPSILON;
  }

  public boolean balancedGrounded(double centerX, double feetY, double centerZ) {
    if (centerGrounded(centerX, feetY, centerZ)) {
      return true;
    }
    double r = Math.max(0.28D, halfWidth - 0.05D);
    boolean west = pointGrounded(centerX - r, feetY, centerZ);
    boolean east = pointGrounded(centerX + r, feetY, centerZ);
    if (west && east) {
      return true;
    }
    boolean north = pointGrounded(centerX, feetY, centerZ - r);
    boolean south = pointGrounded(centerX, feetY, centerZ + r);
    if (north && south) {
      return true;
    }
    return pointGrounded(centerX - r, feetY, centerZ - r) && pointGrounded(centerX + r, feetY, centerZ + r)
      || pointGrounded(centerX - r, feetY, centerZ + r) && pointGrounded(centerX + r, feetY, centerZ - r);
  }

  public int groundSupportQuadrants(double centerX, double feetY, double centerZ) {
    double r = Math.max(0.28D, halfWidth - 0.05D);
    int supported = 0;
    supported += pointGrounded(centerX - r, feetY, centerZ - r) ? 1 : 0;
    supported += pointGrounded(centerX + r, feetY, centerZ - r) ? 1 : 0;
    supported += pointGrounded(centerX - r, feetY, centerZ + r) ? 1 : 0;
    supported += pointGrounded(centerX + r, feetY, centerZ + r) ? 1 : 0;
    return supported;
  }

  private boolean centerGrounded(double centerX, double feetY, double centerZ) {
    double minX = centerX - 0.25D;
    double minZ = centerZ - 0.25D;
    double maxX = centerX + 0.25D;
    double maxZ = centerZ + 0.25D;
    byte fast = fastGrounded(minX, feetY - GROUND_PROBE, minZ, maxX, feetY + Math.min(0.25D, height), maxZ, feetY);
    if (fast != FAST_GROUNDED_UNKNOWN) {
      return fast == FAST_GROUNDED_TRUE;
    }
    AABB core = new AABB(minX, feetY, minZ, maxX, feetY + Math.min(0.25D, height), maxZ);
    collect(core.expandTowards(0D, -GROUND_PROBE, 0D), colliders);
    if (colliders.isEmpty()) {
      return false;
    }
    return collide(Direction.Axis.Y, core, colliders, -GROUND_PROBE) > -GROUND_PROBE + EPSILON;
  }

  private boolean pointGrounded(double centerX, double feetY, double centerZ) {
    double minX = centerX - 0.04D;
    double minZ = centerZ - 0.04D;
    double maxX = centerX + 0.04D;
    double maxZ = centerZ + 0.04D;
    byte fast = fastGrounded(minX, feetY - GROUND_PROBE, minZ, maxX, feetY + Math.min(0.16D, height), maxZ, feetY);
    if (fast != FAST_GROUNDED_UNKNOWN) {
      return fast == FAST_GROUNDED_TRUE;
    }
    AABB point = new AABB(minX, feetY, minZ, maxX, feetY + Math.min(0.16D, height), maxZ);
    collect(point.expandTowards(0D, -GROUND_PROBE, 0D), colliders);
    if (colliders.isEmpty()) {
      return false;
    }
    return collide(Direction.Axis.Y, point, colliders, -GROUND_PROBE) > -GROUND_PROBE + EPSILON;
  }

  boolean waterSupport(double centerX, int feetY, double centerZ) {
    return waterFloatSupport(centerX, feetY, centerZ);
  }

  boolean surfaceWaterSupport(double centerX, int feetY, double centerZ) {
    return waterFloatSupport(centerX, feetY, centerZ);
  }

  private boolean waterFloatSupport(double centerX, int feetY, double centerZ) {
    int minX = Mth.floor(centerX - halfWidth + EPSILON);
    int maxX = Mth.floor(centerX + halfWidth - EPSILON);
    int minZ = Mth.floor(centerZ - halfWidth + EPSILON);
    int maxZ = Mth.floor(centerZ + halfWidth - EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (surfaceWater(x, feetY, z)) {
          return true;
        }
      }
    }
    return false;
  }

  private boolean centerWaterSupport(double centerX, int feetY, double centerZ) {
    int x = Mth.floor(centerX);
    int z = Mth.floor(centerZ);
    return surfaceWater(x, feetY, z);
  }

  private boolean balancedWaterSupport(double centerX, int feetY, double centerZ) {
    return centerWaterSupport(centerX, feetY, centerZ) || waterSupport(centerX, feetY, centerZ);
  }

  boolean wetHull(double centerX, int feetY, double centerZ) {
    return hullTouchesWater(centerX, feetY, centerZ) || waterSupport(centerX, feetY, centerZ);
  }

  private boolean hullTouchesWater(double centerX, int feetY, double centerZ) {
    int minX = Mth.floor(centerX - halfWidth + EPSILON);
    int maxX = Mth.floor(centerX + halfWidth - EPSILON);
    int minY = Mth.floor(feetY + EPSILON);
    int maxY = Mth.floor(feetY + height - EPSILON);
    int minZ = Mth.floor(centerZ - halfWidth + EPSILON);
    int maxZ = Mth.floor(centerZ + halfWidth - EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int y = minY; y <= maxY; y++) {
        for (int z = minZ; z <= maxZ; z++) {
          if (water(x, y, z)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public Move clippedStep(double centerX, double feetY, double centerZ, double dx, double dz, boolean grounded) {
    AABB box = box(centerX, feetY, centerZ);
    Vec3 desired = new Vec3(dx, 0D, dz);
    collect(box.expandTowards(desired), colliders);
    Vec3 ordinary = collideWithShapes(desired, box, colliders);
    if (stepHeight <= 0D || (!grounded && ordinary.y >= -EPSILON) || horizontalUnclipped(desired, ordinary)) {
      return new Move(ordinary.x, ordinary.y, ordinary.z);
    }
    AABB base = ordinary.y < -EPSILON ? box.move(0D, ordinary.y, 0D) : box;
    AABB sweptStep = base.expandTowards(dx, stepHeight, dz);
    if (ordinary.y >= -EPSILON) {
      sweptStep = sweptStep.expandTowards(0D, -1.0E-5D, 0D);
    }
    collect(sweptStep, stepColliders);
    int count = collectStepHeights(base, stepColliders, Math.max(0F, (float) ordinary.y));
    Vec3 best = ordinary;
    double bestHorizontal = ordinary.horizontalDistanceSqr();
    for (int i = 0; i < count; i++) {
      float step = stepHeights[i];
      Vec3 candidate = collideWithShapes(new Vec3(dx, step, dz), base, stepColliders);
      double horizontal = candidate.horizontalDistanceSqr();
      if (horizontal <= bestHorizontal + EPSILON) {
        continue;
      }
      double baseShift = box.minY - base.minY;
      best = candidate.subtract(0D, baseShift, 0D);
      bestHorizontal = horizontal;
      break;
    }
    return new Move(best.x, best.y, best.z);
  }

  public boolean clearSegment(double sx, double sy, double sz, double ex, double ey, double ez) {
    int samples = Math.max(2, (int) Math.ceil(Math.max(Math.hypot(ex - sx, ez - sz), Math.abs(ey - sy)) * 6D));
    for (int i = 0; i <= samples; i++) {
      double t = i / (double) samples;
      if (!clearPose(Mth.lerp(t, sx, ex), Mth.lerp(t, sy, ey), Mth.lerp(t, sz, ez))) {
        return false;
      }
    }
    return true;
  }

  public boolean clearHazardSegment(double sx, double sy, double sz, double ex, double ey, double ez, double horizontalMargin) {
    int samples = Math.max(2, (int) Math.ceil(Math.max(Math.hypot(ex - sx, ez - sz), Math.abs(ey - sy)) * 6D));
    for (int i = 0; i <= samples; i++) {
      double t = i / (double) samples;
      if (hazardsIntersect(box(Mth.lerp(t, sx, ex), Mth.lerp(t, sy, ey), Mth.lerp(t, sz, ez)), horizontalMargin)) {
        return false;
      }
    }
    return true;
  }

  private boolean horizontalUnclipped(Vec3 desired, Vec3 actual) {
    return Math.abs(desired.x - actual.x) < EPSILON && Math.abs(desired.z - actual.z) < EPSILON;
  }

  private int collectStepHeights(AABB base, ArrayList<VoxelShape> shapes, float collidedY) {
    int count = 0;
    for (VoxelShape shape : shapes) {
      for (double y : shape.getCoords(Direction.Axis.Y)) {
        float step = (float) (y - base.minY);
        if (step < 0F || step == collidedY || step > stepHeight + 1.0E-5D || containsStepHeight(step, count)) {
          continue;
        }
        if (count == stepHeights.length) {
          break;
        }
        stepHeights[count++] = step;
      }
    }
    java.util.Arrays.sort(stepHeights, 0, count);
    return count;
  }

  private boolean containsStepHeight(float step, int count) {
    for (int i = 0; i < count; i++) {
      if (stepHeights[i] == step) {
        return true;
      }
    }
    return false;
  }

  private Vec3 collideWithShapes(Vec3 desired, AABB box, ArrayList<VoxelShape> shapes) {
    if (shapes.isEmpty()) {
      return desired;
    }
    double x = 0D;
    double y = 0D;
    double z = 0D;
    y = collide(Direction.Axis.Y, box, shapes, desired.y);
    AABB yBox = box.move(0D, y, 0D);
    if (Math.abs(desired.x) < Math.abs(desired.z)) {
      z = collide(Direction.Axis.Z, yBox, shapes, desired.z);
      x = collide(Direction.Axis.X, yBox.move(0D, 0D, z), shapes, desired.x);
    } else {
      x = collide(Direction.Axis.X, yBox, shapes, desired.x);
      z = collide(Direction.Axis.Z, yBox.move(x, 0D, 0D), shapes, desired.z);
    }
    return new Vec3(x, y, z);
  }

  private double collide(Direction.Axis axis, AABB box, ArrayList<VoxelShape> shapes, double delta) {
    if (delta == 0D) {
      return 0D;
    }
    return Shapes.collide(axis, box, shapes, delta);
  }

  private boolean intersects(AABB box) {
    int minX = Mth.floor(box.minX - EPSILON);
    int maxX = Mth.floor(box.maxX + EPSILON);
    int minY = Mth.floor(box.minY - EPSILON);
    int maxY = Mth.floor(box.maxY + EPSILON);
    int minZ = Mth.floor(box.minZ - EPSILON);
    int maxZ = Mth.floor(box.maxZ + EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        boolean hasData = blocks.hasData(x, z);
        for (int y = minY; y <= maxY; y++) {
          if (!hasData) {
            if (unitIntersects(box, x, y, z)) {
              return true;
            }
            continue;
          }
          byte fast = fastShape(x, y, z);
          if (fast == FAST_EXACT) {
            return intersectsExact(box);
          }
          if (fastShapeIntersects(fast, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, x, y, z)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean intersectsExact(AABB box) {
    VoxelShape hull = null;
    int minX = Mth.floor(box.minX - EPSILON);
    int maxX = Mth.floor(box.maxX + EPSILON);
    int minY = Mth.floor(box.minY - EPSILON);
    int maxY = Mth.floor(box.maxY + EPSILON);
    int minZ = Mth.floor(box.minZ - EPSILON);
    int maxZ = Mth.floor(box.maxZ + EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        boolean hasData = blocks.hasData(x, z);
        for (int y = minY; y <= maxY; y++) {
          if (!hasData) {
            if (unitIntersects(box, x, y, z)) {
              return true;
            }
            continue;
          }
          BlockState state = blocks.get(x, y, z);
          if (state.isAir()) {
            continue;
          }
          boolean water = state.getFluidState().is(FluidTags.WATER);
          if (!water && blocks.avoidWalkingInto(x, y, z, state)) {
            if (unitIntersects(box, x, y, z)) {
              return true;
            }
            continue;
          }
          if (state.getBlock() instanceof LiquidBlock) {
            continue;
          }
          probe.set(x, y, z);
          VoxelShape shape = state.getCollisionShape(blocks.access(), probe);
          if (shape.isEmpty()) {
            continue;
          }
          if ((shape == Shapes.block() || state.isCollisionShapeFullBlock(blocks.access(), probe)) && unitIntersects(box, x, y, z)) {
            return true;
          }
          if (hull == null) {
            hull = Shapes.create(box);
          }
          if (Shapes.joinIsNotEmpty(hull, shape.move(x, y, z), BooleanOp.AND)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean hazardsIntersect(AABB box, double horizontalMargin) {
    double minBoxX = box.minX - horizontalMargin;
    double minBoxY = box.minY;
    double minBoxZ = box.minZ - horizontalMargin;
    double maxBoxX = box.maxX + horizontalMargin;
    double maxBoxY = box.maxY;
    double maxBoxZ = box.maxZ + horizontalMargin;
    int minX = Mth.floor(minBoxX - EPSILON);
    int maxX = Mth.floor(maxBoxX + EPSILON);
    int minY = Mth.floor(minBoxY - EPSILON);
    int maxY = Mth.floor(maxBoxY + EPSILON);
    int minZ = Mth.floor(minBoxZ - EPSILON);
    int maxZ = Mth.floor(maxBoxZ + EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        if (!blocks.hasData(x, z)) {
          continue;
        }
        for (int y = minY; y <= maxY; y++) {
          if (fastShape(x, y, z) == FAST_HAZARD_FULL && unitIntersects(minBoxX, minBoxY, minBoxZ, maxBoxX, maxBoxY, maxBoxZ, x, y, z)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private boolean riderSuffocating(double centerX, double feetY, double centerZ) {
    AABB eye = AABB.ofSize(new Vec3(centerX, feetY + MOUNTED_PLAYER_EYE_Y, centerZ), RIDER_SUFFOCATION_CERTIFICATION_WIDTH, PLAYER_SUFFOCATION_BOX_HEIGHT, RIDER_SUFFOCATION_CERTIFICATION_WIDTH);
    int minX = Mth.floor(eye.minX - EPSILON);
    int maxX = Mth.floor(eye.maxX + EPSILON);
    int minY = Mth.floor(eye.minY - EPSILON);
    int maxY = Mth.floor(eye.maxY + EPSILON);
    int minZ = Mth.floor(eye.minZ - EPSILON);
    int maxZ = Mth.floor(eye.maxZ + EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        boolean hasData = blocks.hasData(x, z);
        for (int y = minY; y <= maxY; y++) {
          if (!hasData) {
            if (unitIntersects(eye, x, y, z)) {
              return true;
            }
            continue;
          }
          byte fast = fastShape(x, y, z);
          if (fast != FAST_AIR && fast != FAST_LIQUID) {
            return riderSuffocatingExact(eye);
          }
        }
      }
    }
    return false;
  }

  private boolean riderSuffocatingExact(AABB eye) {
    VoxelShape eyeShape = null;
    int minX = Mth.floor(eye.minX - EPSILON);
    int maxX = Mth.floor(eye.maxX + EPSILON);
    int minY = Mth.floor(eye.minY - EPSILON);
    int maxY = Mth.floor(eye.maxY + EPSILON);
    int minZ = Mth.floor(eye.minZ - EPSILON);
    int maxZ = Mth.floor(eye.maxZ + EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        boolean hasData = blocks.hasData(x, z);
        for (int y = minY; y <= maxY; y++) {
          if (!hasData) {
            if (unitIntersects(eye, x, y, z)) {
              return true;
            }
            continue;
          }
          BlockState state = blocks.get(x, y, z);
          if (state.isAir()) {
            continue;
          }
          probe.set(x, y, z);
          if (!state.isSuffocating(blocks.access(), probe)) {
            continue;
          }
          VoxelShape shape = state.getCollisionShape(blocks.access(), probe);
          if (shape.isEmpty()) {
            continue;
          }
          if ((shape == Shapes.block() || state.isCollisionShapeFullBlock(blocks.access(), probe)) && unitIntersects(eye, x, y, z)) {
            return true;
          }
          if (eyeShape == null) {
            eyeShape = Shapes.create(eye);
          }
          if (Shapes.joinIsNotEmpty(shape.move(x, y, z), eyeShape, BooleanOp.AND)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static boolean unitIntersects(AABB box, int x, int y, int z) {
    return unitIntersects(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, x, y, z);
  }

  private static boolean unitIntersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int x, int y, int z) {
    return maxX > x + EPSILON && minX < x + 1D - EPSILON && maxY > y + EPSILON && minY < y + 1D - EPSILON && maxZ > z + EPSILON && minZ < z + 1D - EPSILON;
  }

  private byte fastGrounded(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double feetY) {
    boolean exact = false;
    boolean supported = false;
    int blockMinX = Mth.floor(minX - EPSILON);
    int blockMaxX = Mth.floor(maxX + EPSILON);
    int blockMinY = Mth.floor(minY - EPSILON);
    int blockMaxY = Mth.floor(maxY + EPSILON);
    int blockMinZ = Mth.floor(minZ - EPSILON);
    int blockMaxZ = Mth.floor(maxZ + EPSILON);
    for (int x = blockMinX; x <= blockMaxX; x++) {
      for (int z = blockMinZ; z <= blockMaxZ; z++) {
        boolean hasData = blocks.hasData(x, z);
        for (int y = blockMinY; y <= blockMaxY; y++) {
          byte fast = hasData ? fastShape(x, y, z) : FAST_FULL;
          if (fast == FAST_EXACT) {
            exact = true;
            continue;
          }
          double top = fastShapeTop(fast, y);
          if (!Double.isFinite(top) || top <= feetY - GROUND_PROBE + EPSILON || top > feetY + EPSILON) {
            continue;
          }
          if (fastShapeHorizontalIntersects(fast, minX, minZ, maxX, maxZ, x, z)) {
            supported = true;
          }
        }
      }
    }
    if (supported) {
      return FAST_GROUNDED_TRUE;
    }
    return exact ? FAST_GROUNDED_UNKNOWN : FAST_GROUNDED_FALSE;
  }

  private byte fastShape(int x, int y, int z) {
    long key = BlockKey.pack(x, y, z);
    byte cached = fastShapeCache.get(key);
    if (cached != 0) {
      return cached;
    }
    byte shape = computeFastShape(x, y, z);
    fastShapeCache.put(key, shape);
    return shape;
  }

  private byte computeFastShape(int x, int y, int z) {
    BlockState state = blocks.get(x, y, z);
    if (state.isAir()) {
      return FAST_AIR;
    }
    boolean water = state.getFluidState().is(FluidTags.WATER);
    if (!water && blocks.avoidWalkingInto(x, y, z, state)) {
      return FAST_HAZARD_FULL;
    }
    if (state.getBlock() instanceof LiquidBlock) {
      return FAST_LIQUID;
    }
    if (state.getBlock() instanceof SlabBlock) {
      return switch (state.getValue(SlabBlock.TYPE)) {
        case DOUBLE -> FAST_FULL;
        case TOP -> FAST_TOP_SLAB;
        case BOTTOM -> FAST_BOTTOM_SLAB;
      };
    }
    probe.set(x, y, z);
    VoxelShape shape = state.getCollisionShape(blocks.access(), probe);
    if (shape.isEmpty()) {
      return FAST_AIR;
    }
    return shape == Shapes.block() || state.isCollisionShapeFullBlock(blocks.access(), probe) ? FAST_FULL : FAST_EXACT;
  }

  private static boolean fastShapeIntersects(byte fast, double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int x, int y, int z) {
    return switch (fast) {
      case FAST_FULL, FAST_HAZARD_FULL -> unitIntersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z);
      case FAST_BOTTOM_SLAB -> boxIntersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, 0D, 0.5D);
      case FAST_TOP_SLAB -> boxIntersects(minX, minY, minZ, maxX, maxY, maxZ, x, y, z, 0.5D, 1D);
      default -> false;
    };
  }

  private static boolean fastShapeHorizontalIntersects(byte fast, double minX, double minZ, double maxX, double maxZ, int x, int z) {
    return switch (fast) {
      case FAST_FULL, FAST_HAZARD_FULL, FAST_BOTTOM_SLAB, FAST_TOP_SLAB -> maxX > x + EPSILON && minX < x + 1D - EPSILON && maxZ > z + EPSILON && minZ < z + 1D - EPSILON;
      default -> false;
    };
  }

  private static double fastShapeTop(byte fast, int y) {
    return switch (fast) {
      case FAST_FULL, FAST_HAZARD_FULL, FAST_TOP_SLAB -> y + 1D;
      case FAST_BOTTOM_SLAB -> y + 0.5D;
      default -> Double.NaN;
    };
  }

  private static VoxelShape fastVoxelShape(byte fast) {
    return switch (fast) {
      case FAST_FULL, FAST_HAZARD_FULL -> Shapes.block();
      case FAST_BOTTOM_SLAB -> BOTTOM_SLAB_SHAPE;
      case FAST_TOP_SLAB -> TOP_SLAB_SHAPE;
      default -> Shapes.empty();
    };
  }

  private static boolean boxIntersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int x, int y, int z, double shapeMinY, double shapeMaxY) {
    return maxX > x + EPSILON && minX < x + 1D - EPSILON && maxY > y + shapeMinY + EPSILON && minY < y + shapeMaxY - EPSILON && maxZ > z + EPSILON && minZ < z + 1D - EPSILON;
  }

  private void collect(AABB box, ArrayList<VoxelShape> out) {
    out.clear();
    int minX = Mth.floor(box.minX - EPSILON);
    int maxX = Mth.floor(box.maxX + EPSILON);
    int minY = Mth.floor(box.minY - EPSILON);
    int maxY = Mth.floor(box.maxY + EPSILON);
    int minZ = Mth.floor(box.minZ - EPSILON);
    int maxZ = Mth.floor(box.maxZ + EPSILON);
    for (int x = minX; x <= maxX; x++) {
      for (int z = minZ; z <= maxZ; z++) {
        boolean hasData = blocks.hasData(x, z);
        for (int y = minY; y <= maxY; y++) {
          if (!hasData) {
            out.add(Shapes.block().move(x, y, z));
            continue;
          }
          byte fast = fastShape(x, y, z);
          if (fast != FAST_EXACT) {
            VoxelShape shape = fastVoxelShape(fast);
            if (!shape.isEmpty()) {
              out.add(shape.move(x, y, z));
            }
            continue;
          }
          BlockState state = blocks.get(x, y, z);
          VoxelShape shape = state.getCollisionShape(blocks.access(), probe.set(x, y, z));
          if (!shape.isEmpty()) {
            out.add(shape.move(x, y, z));
          }
        }
      }
    }
  }

  private boolean water(int x, int y, int z) {
    if (!blocks.hasData(x, z)) {
      return false;
    }
    long key = BlockKey.pack(x, y, z);
    byte cached = waterCache.get(key);
    if (cached != 0) {
      return cached == 1;
    }
    boolean water = blocks.get(x, y, z).getFluidState().is(FluidTags.WATER);
    waterCache.put(key, water ? (byte) 1 : (byte) 2);
    return water;
  }

  private boolean surfaceWater(int x, int y, int z) {
    long key = BlockKey.pack(x, y, z);
    byte cached = surfaceWaterCache.get(key);
    if (cached != 0) {
      return cached == 1;
    }
    if (!water(x, y, z)) {
      surfaceWaterCache.put(key, (byte) 2);
      return false;
    }
    for (int up = 1; up <= WATER_FLOAT_SUPPORT_DEPTH_BLOCKS; up++) {
      if (!water(x, y + up, z)) {
        surfaceWaterCache.put(key, (byte) 1);
        return true;
      }
    }
    surfaceWaterCache.put(key, (byte) 2);
    return false;
  }

  public record Move(double dx, double dy, double dz) {
    public boolean reached(double tx, double tz) {
      return Math.abs(dx - tx) < 0.08D && Math.abs(dz - tz) < 0.08D;
    }
  }

  private interface Blocks {
    BlockState get(int x, int y, int z);

    boolean hasData(int x, int z);

    boolean avoidWalkingInto(int x, int y, int z, BlockState state);

    BlockGetter access();
  }

  private record CalculationBlocks(CalculationContext context) implements Blocks {
    @Override
    public BlockState get(int x, int y, int z) {
      return context.get(x, y, z);
    }

    @Override
    public boolean hasData(int x, int z) {
      return context.worldBorder.entirelyContains(x, z) && context.hasPathingData(x, z);
    }

    @Override
    public boolean avoidWalkingInto(int x, int y, int z, BlockState state) {
      return context.affordances.avoidWalkingInto(x, y, z, state);
    }

    @Override
    public BlockGetter access() {
      return context.bsi.access;
    }
  }

  private static final class LiveBlocks implements Blocks {
    private final Level world;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    private LiveBlocks(Level world) {
      this.world = world;
    }

    @Override
    public BlockState get(int x, int y, int z) {
      return world.getBlockState(pos.set(x, y, z));
    }

    @Override
    public boolean hasData(int x, int z) {
      return true;
    }

    @Override
    public boolean avoidWalkingInto(int x, int y, int z, BlockState state) {
      return false;
    }

    @Override
    public BlockGetter access() {
      return world;
    }
  }
}
