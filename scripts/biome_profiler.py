#!/usr/bin/env python3
from __future__ import annotations

import argparse
import gzip
import io
import json
import math
import random
import statistics
import struct
import zlib
from collections import Counter, defaultdict
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any

nbtlib = None


def require_nbtlib():
  global nbtlib
  if nbtlib is not None:
    return nbtlib
  try:
    import nbtlib as loaded
  except ModuleNotFoundError as e:
    raise SystemExit("nbtlib is required for scan-world; use scripts/playtest to bootstrap the playtest Python environment or install nbtlib") from e
  nbtlib = loaded
  return loaded


AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}
FLUID = {"minecraft:water", "minecraft:lava"}
CAVE_BIOMES = {"minecraft:deep_dark", "minecraft:dripstone_caves", "minecraft:lush_caves"}
UNSTABLE_FLOOR_FRAGMENTS = (
  "leaves",
  "leaf_litter",
  "cactus",
  "magma_block",
  "campfire",
  "fire",
  "berry_bush",
  "powder_snow",
  "cobweb",
)
TREE_FLOOR_FRAGMENTS = (
  "_log",
  "_wood",
  "_stem",
  "hyphae",
  "wart_block",
)
PASSABLE_EXACT = {
  "minecraft:air",
  "minecraft:cave_air",
  "minecraft:void_air",
  "minecraft:snow",
}
PASSABLE_FRAGMENTS = (
  "short_grass",
  "tall_grass",
  "fern",
  "flower",
  "sapling",
  "mushroom",
  "bush",
  "vines",
  "vine",
  "torch",
  "carpet",
  "button",
  "lever",
  "pressure_plate",
  "tripwire",
  "sign",
  "seagrass",
  "kelp",
  "lily_pad",
)
STANDARD_SURFACE_LOADOUT = (
  {"slot": 0, "item": "minecraft:iron_pickaxe", "count": 1},
  {"slot": 1, "item": "minecraft:iron_axe", "count": 1},
  {"slot": 2, "item": "minecraft:iron_shovel", "count": 1},
  {"slot": 3, "item": "minecraft:cobblestone", "count": 64},
  {"slot": 4, "item": "minecraft:cobblestone", "count": 64},
)


@dataclass(frozen=True)
class Surface:
  x: int
  y: int
  z: int
  biome: str


@dataclass(frozen=True)
class SurfaceIndex:
  by_biome: dict[str, list[Surface]]
  by_column: dict[tuple[int, int], Surface]

  def nearest(self, x: int, z: int, biome: str, radius: int) -> Surface | None:
    for dx, dz in spiral_offsets(radius):
      surface = self.by_column.get((x + dx, z + dz))
      if surface is not None and surface.biome == biome:
        return surface
    return None


@dataclass(frozen=True)
class Span:
  biome: str
  start: Surface
  goal: Surface
  distance: float
  heading: float
  octant: int
  index: int


@dataclass(frozen=True)
class IndependentBiomeSample:
  index: int
  biome: str
  x: int
  y: int
  z: int

  def id(self, world_key: str) -> str:
    return f"anchor:{safe_id(self.biome)}:{world_key}:{self.index}:{self.x}:{self.y}:{self.z}"


class EndpointExclusion:
  def __init__(self, radius: float):
    self.radius = radius
    self.radius_squared = radius * radius
    self.cell = max(1, math.ceil(radius))
    self.by_cell: dict[tuple[int, int], list[tuple[int, int]]] = defaultdict(list)

  def blocked(self, x: int, z: int) -> bool:
    if self.radius <= 0:
      return False
    cx = math.floor(x / self.cell)
    cz = math.floor(z / self.cell)
    for dx in (-1, 0, 1):
      for dz in (-1, 0, 1):
        for ox, oz in self.by_cell.get((cx + dx, cz + dz), ()):
          if (x - ox) * (x - ox) + (z - oz) * (z - oz) < self.radius_squared:
            return True
    return False

  def occupy(self, x: int, z: int) -> None:
    if self.radius <= 0:
      return
    self.by_cell[(math.floor(x / self.cell), math.floor(z / self.cell))].append((x, z))


def read_independent_samples(path: Path | None) -> tuple[IndependentBiomeSample, ...]:
  if path is None or not path.exists():
    return ()
  samples: list[IndependentBiomeSample] = []
  for index, raw in enumerate(path.read_text(encoding="utf-8").splitlines()):
    if not raw.strip():
      continue
    parts = raw.split("\t")
    if len(parts) >= 4:
      samples.append(IndependentBiomeSample(index, parts[0], int(parts[1]), int(parts[2]), int(parts[3])))
      continue
    if len(parts) >= 2:
      samples.append(IndependentBiomeSample(index, "minecraft:unknown", int(parts[0]), 0, int(parts[1])))
  return tuple(samples)


def independent_sample_id(samples: tuple[IndependentBiomeSample, ...], args: argparse.Namespace, span: Span) -> str:
  mx = (span.start.x + span.goal.x) / 2
  mz = (span.start.z + span.goal.z) / 2
  matching = [sample for sample in samples if sample.biome == span.biome or sample.biome == "minecraft:unknown"]
  if matching:
    return min(matching, key=lambda sample: (mx - sample.x) * (mx - sample.x) + (mz - sample.z) * (mz - sample.z)).id(args.world_key)
  cell = max(1, int(args.max_distance * 4))
  return f"synthetic:{safe_id(span.biome)}:{args.world_key}:{math.floor(mx / cell)}:{math.floor(mz / cell)}"


def unpack(data: Any, bits: int, index: int) -> int:
  if bits == 0:
    return 0
  values_per_long = 64 // bits
  long_index = index // values_per_long
  bit_index = (index % values_per_long) * bits
  value = int(data[long_index])
  if value < 0:
    value += 1 << 64
  return (value >> bit_index) & ((1 << bits) - 1)


def state_name(entry: Any) -> str:
  return str(entry.get("Name", "minecraft:air"))


def bare_block(name: str) -> str:
  return name.split("[", 1)[0]


def airlike(name: str) -> bool:
  b = bare_block(name)
  return b in PASSABLE_EXACT or any(fragment in b for fragment in PASSABLE_FRAGMENTS)


def fluid(name: str) -> bool:
  return bare_block(name) in FLUID


def stable_floor(name: str) -> bool:
  b = bare_block(name)
  return not airlike(b) and not fluid(b) and not any(fragment in b for fragment in UNSTABLE_FLOOR_FRAGMENTS)


def spawn_floor(name: str) -> bool:
  b = bare_block(name)
  return stable_floor(b) and not any(fragment in b for fragment in TREE_FLOOR_FRAGMENTS)


def body_clear(name: str) -> bool:
  return airlike(name) and not fluid(name)


@lru_cache(maxsize=64)
def spiral_offsets(radius: int) -> tuple[tuple[int, int], ...]:
  offsets = [(0, 0)]
  for r in range(1, radius + 1):
    for x in range(-r, r + 1):
      offsets.append((x, -r))
      offsets.append((x, r))
    for z in range(-r + 1, r):
      offsets.append((-r, z))
      offsets.append((r, z))
  return tuple(dict.fromkeys(offsets))


class Chunk:
  def __init__(self, nbt: Any):
    self.nbt = nbt
    self.cx = int(nbt["xPos"])
    self.cz = int(nbt["zPos"])
    self.sections = {int(section["Y"]): section for section in nbt.get("sections", [])}
    self.min_sy = min(self.sections, default=-4)
    self.max_sy = max(self.sections, default=20)
    self.min_y = int(nbt.get("yPos", self.min_sy)) * 16
    self.heightmap_bits = max(1, (((self.max_sy - self.min_sy + 1) * 16) + 1).bit_length())

  def block(self, x: int, y: int, z: int) -> str:
    section = self.sections.get(y // 16)
    if section is None or "block_states" not in section:
      return "minecraft:air"
    states = section["block_states"]
    palette = states.get("palette", [])
    if not palette:
      return "minecraft:air"
    if len(palette) == 1:
      return state_name(palette[0])
    bits = max(4, (len(palette) - 1).bit_length())
    data = states.get("data")
    if data is None:
      return state_name(palette[0])
    local_index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
    palette_index = unpack(data, bits, local_index)
    return state_name(palette[palette_index]) if palette_index < len(palette) else "minecraft:air"

  def biome(self, x: int, y: int, z: int) -> str:
    section = self.sections.get(y // 16)
    if section is None or "biomes" not in section:
      return "minecraft:plains"
    biomes = section["biomes"]
    palette = biomes.get("palette", [])
    if not palette:
      return "minecraft:plains"
    if len(palette) == 1:
      return str(palette[0])
    bits = max(1, (len(palette) - 1).bit_length())
    data = biomes.get("data")
    if data is None:
      return str(palette[0])
    lx = (x & 15) >> 2
    ly = (y & 15) >> 2
    lz = (z & 15) >> 2
    palette_index = unpack(data, bits, (ly << 4) | (lz << 2) | lx)
    return str(palette[palette_index]) if palette_index < len(palette) else str(palette[0])

  def heightmap_y(self, key: str, x: int, z: int) -> int | None:
    heightmaps = self.nbt.get("Heightmaps")
    if not heightmaps or key not in heightmaps:
      return None
    index = (x & 15) + ((z & 15) * 16)
    return self.min_y + unpack(heightmaps[key], self.heightmap_bits, index)


class WorldReader:
  def __init__(self, world: Path, dimension: str, min_surface_y: int = 50, max_surface_y: int = 180):
    self.world = world
    self.dimension = dimension
    self.min_surface_y = min_surface_y
    self.max_surface_y = max_surface_y
    self.regions = self.region_root()
    self._generated_chunks: tuple[tuple[int, int], ...] | None = None

  @property
  def generated_chunks(self) -> tuple[tuple[int, int], ...]:
    if self._generated_chunks is None:
      self._generated_chunks = tuple(self.chunk_keys())
    return self._generated_chunks

  def region_root(self) -> Path:
    candidates = [self.world / "dimensions" / "minecraft" / "overworld" / "region", self.world / "region"] if self.dimension == "minecraft:overworld" else []
    if ":" in self.dimension:
      namespace, path = self.dimension.split(":", 1)
      candidates.append(self.world / "dimensions" / namespace / path / "region")
    for candidate in candidates:
      if candidate.is_dir():
        return candidate
    raise SystemExit(f"no region directory for {self.dimension} under {self.world}")

  def chunk_keys(self) -> list[tuple[int, int]]:
    keys: list[tuple[int, int]] = []
    for region in sorted(self.regions.glob("r.*.*.mca")):
      parts = region.stem.split(".")
      rx, rz = int(parts[1]), int(parts[2])
      with region.open("rb") as file:
        for index in range(1024):
          file.seek(index * 4)
          location = file.read(4)
          if len(location) != 4:
            continue
          if int.from_bytes(location[:3], "big") == 0 or location[3] == 0:
            continue
          keys.append((rx * 32 + (index & 31), rz * 32 + (index >> 5)))
    return keys

  @lru_cache(maxsize=65536)
  def chunk(self, cx: int, cz: int) -> Chunk | None:
    region = self.regions / f"r.{math.floor(cx / 32)}.{math.floor(cz / 32)}.mca"
    if not region.exists():
      return None
    index = (cx & 31) + ((cz & 31) * 32)
    with region.open("rb") as file:
      file.seek(index * 4)
      location = file.read(4)
      if len(location) != 4:
        return None
      offset = int.from_bytes(location[:3], "big")
      sectors = location[3]
      if offset == 0 or sectors == 0:
        return None
      file.seek(offset * 4096)
      length = struct.unpack(">I", file.read(4))[0]
      compression = file.read(1)[0]
      data = file.read(length - 1)
    match compression:
      case 1:
        data = gzip.decompress(data)
      case 2:
        data = zlib.decompress(data)
      case 3:
        pass
      case other:
        raise SystemExit(f"unsupported chunk compression {other} in {region}")
    return Chunk(require_nbtlib().File.parse(io.BytesIO(data)))

  def block(self, x: int, y: int, z: int) -> str:
    chunk = self.chunk(math.floor(x / 16), math.floor(z / 16))
    return "minecraft:air" if chunk is None else chunk.block(x, y, z)

  @lru_cache(maxsize=1_000_000)
  def surface(self, x: int, z: int) -> Surface | None:
    chunk = self.chunk(math.floor(x / 16), math.floor(z / 16))
    if chunk is None:
      return None
    surface = self.exterior_surface(x, z, chunk)
    if surface is not None:
      return surface
    return None

  @lru_cache(maxsize=1_000_000)
  def column_biome(self, x: int, z: int) -> str | None:
    chunk = self.chunk(math.floor(x / 16), math.floor(z / 16))
    if chunk is None:
      return None
    feet_y = chunk.heightmap_y("MOTION_BLOCKING_NO_LEAVES", x, z)
    if feet_y is None or feet_y < self.min_surface_y or feet_y > self.max_surface_y:
      return None
    return chunk.biome(x, feet_y, z)

  def exterior_surface(self, x: int, z: int, chunk: Chunk | None = None) -> Surface | None:
    if chunk is None:
      chunk = self.chunk(math.floor(x / 16), math.floor(z / 16))
    if chunk is None:
      return None
    feet_y = chunk.heightmap_y("MOTION_BLOCKING_NO_LEAVES", x, z)
    if feet_y is None or feet_y < self.min_surface_y or feet_y > self.max_surface_y:
      return None
    floor = self.block(x, feet_y - 1, z)
    feet = self.block(x, feet_y, z)
    head = self.block(x, feet_y + 1, z)
    if not spawn_floor(floor) or not body_clear(feet) or not body_clear(head) or not self.sky_clear(x, feet_y + 2, z, chunk):
      return None
    return Surface(x, feet_y, z, chunk.biome(x, feet_y, z))

  def sky_clear(self, x: int, from_y: int, z: int, chunk: Chunk) -> bool:
    world_surface = chunk.heightmap_y("WORLD_SURFACE", x, z)
    return world_surface is not None and world_surface <= from_y

  def nearest_surface(self, x: int, z: int, biome: str, radius: int) -> Surface | None:
    for dx, dz in spiral_offsets(radius):
      surface = self.surface(x + dx, z + dz)
      if surface is not None and surface.biome == biome:
        return surface
    return None

  @lru_cache(maxsize=1_000_000)
  def legacy_standing_surface(self, x: int, z: int) -> Surface | None:
    chunk = self.chunk(math.floor(x / 16), math.floor(z / 16))
    if chunk is None:
      return None
    top = min(chunk.max_sy * 16 + 15, self.max_surface_y)
    bottom = max(chunk.min_sy * 16, self.min_surface_y)
    for feet_y in range(top, bottom - 1, -1):
      floor = self.block(x, feet_y - 1, z)
      feet = self.block(x, feet_y, z)
      head = self.block(x, feet_y + 1, z)
      if stable_floor(floor) and airlike(feet) and not fluid(feet) and airlike(head) and not fluid(head):
        return Surface(x, feet_y, z, chunk.biome(x, feet_y, z))
    return None


def octant(angle: float) -> int:
  return int(((angle + math.pi * 2) % (math.pi * 2)) / (math.pi / 4) + 0.5) & 7


def pure_line(index: SurfaceIndex, biome: str, sx: int, sz: int, gx: int, gz: int, step: int, spiral_radius: int) -> bool:
  distance = math.hypot(gx - sx, gz - sz)
  samples = max(2, math.ceil(distance / step))
  for i in range(samples + 1):
    t = i / samples
    x = round(sx + (gx - sx) * t)
    z = round(sz + (gz - sz) * t)
    if index.nearest(x, z, biome, spiral_radius) is None:
      return False
  return True


def scan_chunks(world: WorldReader, args: argparse.Namespace) -> tuple[tuple[int, int], ...]:
  tile_file = getattr(args, "chunk_tile_file", None)
  if tile_file is None:
    return world.generated_chunks
  if not tile_file.exists():
    raise SystemExit(f"missing chunk tile file: {tile_file}")
  allowed: set[tuple[int, int]] = set()
  for raw in tile_file.read_text(encoding="utf-8").splitlines():
    if not raw.strip():
      continue
    cx, cz = (int(part) for part in raw.split())
    for dx in range(args.tile_chunks):
      for dz in range(args.tile_chunks):
        allowed.add((cx + dx, cz + dz))
  return tuple(sorted(allowed))


def discover(args: argparse.Namespace) -> None:
  rng = random.Random(args.random_seed)
  world = WorldReader(args.world, args.dimension, args.min_surface_y, args.max_surface_y)
  independent_samples = read_independent_samples(args.anchor_file)
  generated_chunks = scan_chunks(world, args)
  by_biome: dict[str, list[Surface]] = defaultdict(list)
  by_column: dict[tuple[int, int], Surface] = {}
  for cx, cz in generated_chunks:
    for lx in range(0, 16, args.scan_stride):
      for lz in range(0, 16, args.scan_stride):
        surface = world.surface(cx * 16 + lx, cz * 16 + lz)
        if surface is not None and (not args.exclude_cave_biomes or surface.biome not in CAVE_BIOMES) and (not args.biome or surface.biome in args.biome):
          by_biome[surface.biome].append(surface)
          by_column[(surface.x, surface.z)] = surface
  surface_index = SurfaceIndex(by_biome, by_column)
  args.out.mkdir(parents=True, exist_ok=True)
  for stale in args.out.glob("biome_profile_*.json"):
    stale.unlink()
  manifest = args.out / "manifest.txt"
  emitted: list[Path] = []
  index_by_biome: dict[str, int] = defaultdict(int)
  for biome in sorted(by_biome, key=lambda b: (-len(by_biome[b]), b)):
    starts = by_biome[biome][:]
    if len(starts) < args.min_biome_samples:
      continue
    rng.shuffle(starts)
    starts = starts[: args.max_starts_per_biome]
    endpoints = EndpointExclusion(args.endpoint_exclusion_radius)
    heading_counts = [0] * 8
    rejections = 0
    for start in starts:
      if index_by_biome[biome] >= args.max_scenarios_per_biome:
        break
      if rejections >= args.max_rejections_per_biome:
        break
      if endpoints.blocked(start.x, start.z):
        continue
      headings = list(range(8))
      headings.sort(key=lambda o: (heading_counts[o], rng.random()))
      accepted = False
      for octant_id in headings:
        if accepted:
          break
        base_angle = octant_id * math.pi / 4
        for _ in range(args.attempts_per_heading):
          distance = rng.uniform(args.min_distance, args.max_distance)
          angle = base_angle + rng.uniform(-math.pi / 12, math.pi / 12)
          gx = round(start.x + math.cos(angle) * distance)
          gz = round(start.z + math.sin(angle) * distance)
          goal = surface_index.nearest(gx, gz, biome, args.spawn_spiral_radius)
          if goal is None or goal.biome != biome:
            rejections += 1
            continue
          actual_distance = math.hypot(goal.x - start.x, goal.z - start.z)
          if actual_distance < args.min_distance or actual_distance > args.max_distance:
            rejections += 1
            continue
          if endpoints.blocked(goal.x, goal.z):
            rejections += 1
            continue
          if not pure_line(surface_index, biome, start.x, start.z, goal.x, goal.z, args.line_stride, args.spawn_spiral_radius):
            rejections += 1
            continue
          heading = math.atan2(goal.z - start.z, goal.x - start.x)
          span = Span(biome, start, goal, actual_distance, heading, octant(heading), index_by_biome[biome])
          path = write_scenario(args, span, independent_sample_id(independent_samples, args, span))
          emitted.append(path)
          index_by_biome[biome] += 1
          heading_counts[span.octant] += 1
          endpoints.occupy(start.x, start.z)
          endpoints.occupy(goal.x, goal.z)
          accepted = True
          break
  manifest.write_text("".join(f"{path.as_posix()}\n" for path in emitted), encoding="utf-8")
  print(
    json.dumps(
      {
        "world": str(args.world),
        "dimension": args.dimension,
        "generatedChunks": len(generated_chunks),
        "worldGeneratedChunks": None if args.chunk_tile_file is not None else len(world.generated_chunks),
        "scenarios": len(emitted),
        "manifest": str(manifest),
      },
      indent=2,
    )
  )


def safe_id(value: str) -> str:
  return "".join(c if c.isalnum() else "_" for c in value.removeprefix("minecraft:")).strip("_")


def coord_id(value: int) -> str:
  return str(value).replace("-", "m")


def write_scenario(args: argparse.Namespace, span: Span, independent_sample: str) -> Path:
  sid = (
    f"biome_profile_{safe_id(span.biome)}"
    f"_{coord_id(span.start.x)}_{coord_id(span.start.y)}_{coord_id(span.start.z)}"
    f"__{coord_id(span.goal.x)}_{coord_id(span.goal.y)}_{coord_id(span.goal.z)}"
  )
  path = args.out / f"{sid}.json"
  scenario = {
    "id": sid,
    "worldKey": args.world_key,
    "seed": args.seed,
    "worldPreset": args.world_preset,
    "dimension": args.dimension,
    "start": {"x": span.start.x + 0.5, "y": float(span.start.y), "z": span.start.z + 0.5, "yaw": 0.0, "pitch": 0.0},
    "goal": {"type": "xz", "x": span.goal.x, "y": span.goal.y, "z": span.goal.z},
    "setupCommands": [
      "difficulty peaceful",
    ],
    "loadout": list(STANDARD_SURFACE_LOADOUT),
    "selectedSlot": 0,
    "timeoutTicks": max(args.min_timeout_ticks, math.ceil(span.distance * args.timeout_ticks_per_block + args.timeout_ticks_constant)),
    "successRadius": args.success_radius,
    "saturationBoost": args.saturation_boost,
    "acceptance": {
      "requireOnGround": True,
      "requireNoVehicle": True,
      "requirePathingSeen": True,
      "requireNoDamage": True,
    },
    "settings": {
      "allowSprint": True,
      "sprintInWater": True,
      "allowBreak": True,
      "allowPlace": True,
      "macroPlanning": False,
      "elytraEnabled": False,
      "primaryTimeoutMS": args.primary_timeout_ms,
      "failureTimeoutMS": args.failure_timeout_ms,
    },
    "trace": args.trace,
    "biomeProfile": {
      "schema": 1,
      "biome": span.biome,
      "distance": span.distance,
      "headingRadians": span.heading,
      "headingOctant": span.octant,
      "lineStride": args.line_stride,
      "scanStride": args.scan_stride,
      "endpointExclusionRadius": args.endpoint_exclusion_radius,
      "spawnSurfaceProfile": "motion_blocking_no_leaves_sky_exposed_v1",
      "spawnSpiralRadius": args.spawn_spiral_radius,
      "independentSample": independent_sample,
      "candidateIndex": span.index,
      "controllerProfile": args.controller_profile,
    },
  }
  path.write_text(json.dumps(scenario, indent=2) + "\n", encoding="utf-8")
  return path


def percentile(values: list[float], q: float) -> float | None:
  if not values:
    return None
  ordered = sorted(values)
  if len(ordered) == 1:
    return ordered[0]
  pos = (len(ordered) - 1) * q
  lo = math.floor(pos)
  hi = math.ceil(pos)
  if lo == hi:
    return ordered[lo]
  return ordered[lo] * (hi - pos) + ordered[hi] * (pos - lo)


def aggregate(args: argparse.Namespace) -> None:
  rows: dict[str, list[dict[str, Any]]] = defaultdict(list)
  for sample_path in args.results.glob("*.json"):
    try:
      sample = json.loads(sample_path.read_text(encoding="utf-8"))
      summary = sample["summary"]
      scenario = sample["scenario"]
    except (KeyError, TypeError, json.JSONDecodeError):
      continue
    profile = scenario.get("biomeProfile")
    if not profile:
      continue
    distance = float(profile["distance"])
    rows[profile["biome"]].append({"scenario": scenario, "profile": profile, "summary": summary, "distance": distance})
  for summary_path in args.results.rglob("summary.json"):
    scenario_path = summary_path.with_name("scenario.json")
    if not scenario_path.exists():
      continue
    scenario = json.loads(scenario_path.read_text(encoding="utf-8"))
    profile = scenario.get("biomeProfile")
    if not profile:
      continue
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    distance = float(profile["distance"])
    rows[profile["biome"]].append({"scenario": scenario, "profile": profile, "summary": summary, "distance": distance})
  out: dict[str, Any] = {
    "schema": 1,
    "minecraftVersion": args.minecraft_version,
    "controllerProfile": args.controller_profile,
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "sourceResults": str(args.results),
    "biomes": {},
  }
  for biome, biome_rows in sorted(rows.items()):
    successes = [row for row in biome_rows if row["summary"].get("success")]
    ticks_per_block = [row["summary"]["elapsedTicks"] / row["distance"] for row in successes]
    total_blocks = sum(row["distance"] for row in successes)
    successful_ticks = sum(row["summary"]["elapsedTicks"] for row in successes)
    independent_samples = {str(row["profile"].get("independentSample", "legacy")) for row in successes}
    damage_count = sum(1 for row in biome_rows if row["summary"].get("tookDamage"))
    failures = len(biome_rows) - len(successes)
    metric = {
      "samples": len(biome_rows),
      "successfulSamples": len(successes),
      "failedSamples": failures,
      "successfulTicks": successful_ticks,
      "targetSuccessfulTicks": args.target_successful_ticks,
      "minimumSuccessfulSamples": args.minimum_successful_samples,
      "budgetComplete": successful_ticks >= args.target_successful_ticks and len(successes) >= args.minimum_successful_samples and len(independent_samples) >= args.minimum_independent_samples,
      "successfulIndependentSamples": len(independent_samples),
      "minimumIndependentSamples": args.minimum_independent_samples,
      "totalBlocks": total_blocks,
      "medianTicksPerBlock": statistics.median(ticks_per_block) if ticks_per_block else None,
      "p25TicksPerBlock": percentile(ticks_per_block, 0.25),
      "p75TicksPerBlock": percentile(ticks_per_block, 0.75),
      "failureRate": failures / len(biome_rows) if biome_rows else None,
      "damageRate": damage_count / len(biome_rows) if biome_rows else None,
      "jumpTicksPerBlock": per_block(successes, "jumpTicks"),
      "sprintTicksPerBlock": per_block(successes, "sprintTicks"),
      "moveForwardTicksPerBlock": per_block(successes, "moveForwardTicks"),
      "waterTicksPerBlock": mode_per_block(successes, ("SWIM", "BOAT", "LEGACY_WATER")),
    }
    out["biomes"][biome] = {"surfacePedestrian": metric}
  args.out.parent.mkdir(parents=True, exist_ok=True)
  args.out.write_text(json.dumps(out, indent=2) + "\n", encoding="utf-8")
  print(json.dumps({"biomes": len(out["biomes"]), "out": str(args.out)}, indent=2))


def export_facts(args: argparse.Namespace) -> None:
  world = WorldReader(args.world, args.dimension, args.min_surface_y, args.max_surface_y)
  cells: list[list[Any]] = []
  for cx, cz in world.generated_chunks:
    samples: list[Surface] = []
    center = world.surface(cx * 16 + 8, cz * 16 + 8)
    if center is not None and (not args.exclude_cave_biomes or center.biome not in CAVE_BIOMES):
      samples.append(center)
    for lx in range(0, 16, args.scan_stride):
      for lz in range(0, 16, args.scan_stride):
        surface = world.surface(cx * 16 + lx, cz * 16 + lz)
        if surface is not None and (not args.exclude_cave_biomes or surface.biome not in CAVE_BIOMES):
          samples.append(surface)
    if not samples:
      continue
    counts = Counter(sample.biome for sample in samples)
    biome, count = counts.most_common(1)[0]
    ys = sorted(sample.y for sample in samples if sample.biome == biome)
    surface_y = ys[len(ys) // 2]
    if count / len(samples) < args.min_biome_fraction:
      continue
    cells.append([cx, cz, biome, surface_y])
  out = {
    "schema": 1,
    "dimension": args.dimension,
    "world": str(args.world),
    "cellBlocks": 16,
    "evidence": args.evidence,
    "minBiomeFraction": args.min_biome_fraction,
    "generatedAt": datetime.now(timezone.utc).isoformat(),
    "cells": cells,
  }
  args.out.parent.mkdir(parents=True, exist_ok=True)
  args.out.write_text(json.dumps(out, separators=(",", ":")) + "\n", encoding="utf-8")
  print(json.dumps({"world": str(args.world), "dimension": args.dimension, "cells": len(cells), "out": str(args.out)}, indent=2))


def per_block(rows: list[dict[str, Any]], field: str) -> float | None:
  total_blocks = sum(row["distance"] for row in rows)
  return None if total_blocks == 0 else sum(row["summary"].get(field, 0) for row in rows) / total_blocks


def mode_per_block(rows: list[dict[str, Any]], modes: tuple[str, ...]) -> float | None:
  total_blocks = sum(row["distance"] for row in rows)
  return None if total_blocks == 0 else sum(sum(row["summary"].get("actualModeTicks", {}).get(mode, 0) for mode in modes) for row in rows) / total_blocks


def parser() -> argparse.ArgumentParser:
  root = argparse.ArgumentParser()
  sub = root.add_subparsers(dest="command", required=True)
  scan = sub.add_parser("scan-world")
  scan.add_argument("--world", type=Path, required=True)
  scan.add_argument("--dimension", default="minecraft:overworld")
  scan.add_argument("--out", type=Path, required=True)
  scan.add_argument("--world-key", required=True)
  scan.add_argument("--seed", required=True)
  scan.add_argument("--world-preset", default="minecraft:large_biomes")
  scan.add_argument("--controller-profile", default="native-pedestrian-2-1")
  scan.add_argument("--biome", action="append", default=[])
  scan.add_argument("--min-distance", type=float, default=200.0)
  scan.add_argument("--max-distance", type=float, default=400.0)
  scan.add_argument("--scan-stride", type=int, default=8)
  scan.add_argument("--line-stride", type=int, default=8)
  scan.add_argument("--min-surface-y", type=int, default=50)
  scan.add_argument("--max-surface-y", type=int, default=180)
  scan.add_argument("--chunk-tile-file", type=Path)
  scan.add_argument("--anchor-file", type=Path)
  scan.add_argument("--tile-chunks", type=int, default=16)
  scan.add_argument("--exclude-cave-biomes", action=argparse.BooleanOptionalAction, default=True)
  scan.add_argument("--endpoint-exclusion-radius", type=float, default=80.0)
  scan.add_argument("--spawn-spiral-radius", type=int, default=16)
  scan.add_argument("--min-biome-samples", type=int, default=32)
  scan.add_argument("--max-starts-per-biome", type=int, default=4000)
  scan.add_argument("--max-rejections-per-biome", type=int, default=10000)
  scan.add_argument("--max-scenarios-per-biome", type=int, default=80)
  scan.add_argument("--attempts-per-heading", type=int, default=8)
  scan.add_argument("--random-seed", type=int, default=1337)
  scan.add_argument("--min-timeout-ticks", type=int, default=2400)
  scan.add_argument("--success-radius", type=float, default=2.0)
  scan.add_argument("--timeout-ticks-per-block", type=float, default=10.0)
  scan.add_argument("--timeout-ticks-constant", type=float, default=600.0)
  scan.add_argument("--primary-timeout-ms", type=int, default=20000)
  scan.add_argument("--failure-timeout-ms", type=int, default=30000)
  scan.add_argument("--saturation-boost", action=argparse.BooleanOptionalAction, default=True)
  scan.add_argument("--trace", action=argparse.BooleanOptionalAction, default=False)
  scan.set_defaults(func=discover)

  agg = sub.add_parser("aggregate")
  agg.add_argument("--results", type=Path, required=True)
  agg.add_argument("--out", type=Path, required=True)
  agg.add_argument("--minecraft-version", default="26.1.2")
  agg.add_argument("--controller-profile", default="native-pedestrian-2-1")
  agg.add_argument("--target-successful-ticks", type=int, default=12000)
  agg.add_argument("--minimum-successful-samples", type=int, default=12)
  agg.add_argument("--minimum-independent-samples", type=int, default=3)
  agg.set_defaults(func=aggregate)

  facts = sub.add_parser("export-facts")
  facts.add_argument("--world", type=Path, required=True)
  facts.add_argument("--dimension", default="minecraft:overworld")
  facts.add_argument("--out", type=Path, required=True)
  facts.add_argument("--scan-stride", type=int, default=4)
  facts.add_argument("--min-surface-y", type=int, default=50)
  facts.add_argument("--max-surface-y", type=int, default=180)
  facts.add_argument("--exclude-cave-biomes", action=argparse.BooleanOptionalAction, default=True)
  facts.add_argument("--min-biome-fraction", type=float, default=0.5)
  facts.add_argument("--evidence", choices=("cached", "predicted"), default="cached")
  facts.set_defaults(func=export_facts)
  return root


def main() -> None:
  args = parser().parse_args()
  args.func(args)


if __name__ == "__main__":
  main()
