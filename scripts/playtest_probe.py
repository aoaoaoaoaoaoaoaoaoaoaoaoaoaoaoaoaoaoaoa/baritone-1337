#!/usr/bin/env python3
from __future__ import annotations

import gzip
import io
import struct
import sys
import zlib
from pathlib import Path
from typing import Any

import nbtlib


def main(argv: list[str] | None = None) -> int:
  argv = sys.argv[1:] if argv is None else argv
  if len(argv) != 5:
    raise SystemExit("usage: playtest_probe.py <worldDir> <dimension> <x> <y> <z>")
  world = Path(argv[0])
  dimension = argv[1]
  x, y, z = map(int, argv[2:5])
  regions = region_root(world, dimension)
  chunk, region = chunk_nbt(regions, x // 16, z // 16)
  print(f"world={world}")
  print(f"dimension={dimension}")
  print(f"region={region}")
  print(f"chunk={x // 16} {z // 16}")
  for yy in range(y + 2, y - 3, -1):
    marker = ">" if yy == y else " "
    print(f"{marker} {x} {yy} {z}: {block_state(chunk, x, yy, z)}")
  return 0


def region_candidates(world: Path, dimension: str) -> list[Path]:
  if dimension == "minecraft:overworld":
    return [world / "dimensions" / "minecraft" / "overworld" / "region", world / "region"]
  namespace, _, path = dimension.partition(":")
  if not namespace or not path:
    raise SystemExit(f"invalid dimension id: {dimension}")
  return [world / "dimensions" / namespace / path / "region"]


def region_root(world: Path, dimension: str) -> Path:
  for candidate in region_candidates(world, dimension):
    if candidate.is_dir():
      return candidate
  raise SystemExit(f"no region directory for {dimension} under {world}")


def chunk_nbt(regions: Path, cx: int, cz: int) -> tuple[Any | None, Path]:
  region = regions / f"r.{cx // 32}.{cz // 32}.mca"
  if not region.exists():
    return None, region
  index = (cx & 31) + ((cz & 31) * 32)
  with region.open("rb") as file:
    file.seek(index * 4)
    location = file.read(4)
    if len(location) != 4:
      return None, region
    offset = int.from_bytes(location[:3], "big")
    sectors = location[3]
    if offset == 0 or sectors == 0:
      return None, region
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
  return nbtlib.File.parse(io.BytesIO(data)), region


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


def format_state(entry: Any) -> str:
  name = str(entry.get("Name", "minecraft:air"))
  props = entry.get("Properties")
  if not props:
    return name
  return f"{name}[" + ",".join(f"{key}={str(value)}" for key, value in sorted(props.items())) + "]"


def block_state(chunk: Any | None, bx: int, by: int, bz: int) -> str:
  if chunk is None:
    return "minecraft:air (uncreated chunk)"
  sy = by // 16
  for section in chunk["sections"]:
    if int(section["Y"]) != sy:
      continue
    states = section["block_states"]
    palette = states["palette"]
    if len(palette) == 1:
      palette_index = 0
    else:
      bits = max(4, (len(palette) - 1).bit_length())
      local_index = ((by & 15) << 8) | ((bz & 15) << 4) | (bx & 15)
      palette_index = unpack(states["data"], bits, local_index)
    if palette_index >= len(palette):
      return f"<corrupt palette index {palette_index}/{len(palette)}>"
    return format_state(palette[palette_index])
  return "minecraft:air (missing section)"


if __name__ == "__main__":
  raise SystemExit(main())
