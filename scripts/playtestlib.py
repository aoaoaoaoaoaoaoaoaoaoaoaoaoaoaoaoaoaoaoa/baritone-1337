from __future__ import annotations

import json
import math
import os
import re
import secrets
import shutil
import signal
import socket
import struct
import subprocess
import sys
import time
import fcntl
from collections import Counter
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


MODE_TICKS_ZERO = {"PEDESTRIAN": 0, "LEGACY_WATER": 0, "SWIM": 0, "BOAT": 0, "ELYTRA": 0}
PLAYER_RE = re.compile(r"[A-Za-z0-9_]{1,16}")


def truth(raw: str | None, default: bool) -> bool:
  return default if raw is None else raw.lower() in {"1", "true", "yes", "on"}


def profiler_size(raw: str) -> float:
  match = re.fullmatch(r"\s*([0-9]+(?:\.[0-9]+)?)\s*([kKmMgG]?)\s*", raw)
  if match is None:
    return 1
  value = float(match.group(1))
  suffix = match.group(2).lower()
  return value * {"": 1, "k": 1024, "m": 1024 * 1024, "g": 1024 * 1024 * 1024}[suffix]


def safe_key(value: str) -> str:
  return re.sub(r"[^A-Za-z0-9_.-]", "_", value)


def path_argument_contains(argument: str, needle: str) -> bool:
  start = argument.find(needle)
  while start >= 0:
    before = argument[start - 1] if start > 0 else ""
    after_index = start + len(needle)
    after = argument[after_index] if after_index < len(argument) else ""
    if before in {"", "=", ":", ",", " "} and after in {"", os.sep}:
      return True
    start = argument.find(needle, start + 1)
  return False


def read_json(path: Path) -> dict:
  with path.open(encoding="utf-8") as file:
    return json.load(file)


def write_json(path: Path, value: Any) -> None:
  path.parent.mkdir(parents=True, exist_ok=True)
  path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def now() -> str:
  return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass(frozen=True)
class PlaytestConfig:
  repo: Path
  root: Path
  gradle_user_home: str
  host: str
  port: int
  rcon_port: int
  rcon_password: str
  rcon_timeout: float
  username: str
  uuid: str
  observer_username: str
  observer_uuid: str
  screen: str
  result_timeout: int
  baseline_x: int
  baseline_y: int
  baseline_z: int
  level_type: str
  view_distance: int
  simulation_distance: int
  max_tick_time: int
  mute_client: bool
  profile_event: str
  profile_target: str
  profiler: str
  profiler_alloc_interval: str
  profiler_cpu_sample_ms: float
  profiler_start_timeout: int

  @staticmethod
  def from_env(repo: Path) -> "PlaytestConfig":
    root = Path(os.environ.get("PLAYTEST_ROOT", repo / "run" / "playtest"))
    if not root.is_absolute():
      root = repo / root
    root.mkdir(parents=True, exist_ok=True)
    root = root.resolve()
    return PlaytestConfig(
      repo=repo,
      root=root,
      gradle_user_home=os.environ.get("GRADLE_USER_HOME", "/home/main/.cache/gradle"),
      host=os.environ.get("PLAYTEST_HOST", "127.0.0.1"),
      port=int(os.environ.get("PLAYTEST_PORT", "25565")),
      rcon_port=int(os.environ.get("PLAYTEST_RCON_PORT", "25575")),
      rcon_password=os.environ.get("PLAYTEST_RCON_PASSWORD", ""),
      rcon_timeout=float(os.environ.get("PLAYTEST_RCON_TIMEOUT", "5")),
      username=os.environ.get("PLAYTEST_USERNAME", "BaritoneTest"),
      uuid=os.environ.get("PLAYTEST_UUID", "8b53c6c6-93e4-3df0-8b0c-415e38e60014"),
      observer_username=os.environ.get("PLAYTEST_OBSERVER_USERNAME", "Shmooooo"),
      observer_uuid=os.environ.get("PLAYTEST_OBSERVER_UUID", "f61d2165-7c93-3385-9866-c0918e509fcc"),
      screen=os.environ.get("PLAYTEST_SCREEN", "854x480x24"),
      result_timeout=int(os.environ.get("PLAYTEST_RESULT_TIMEOUT", "600")),
      baseline_x=int(os.environ.get("PLAYTEST_BASELINE_X", "20")),
      baseline_y=int(os.environ.get("PLAYTEST_BASELINE_Y", "114")),
      baseline_z=int(os.environ.get("PLAYTEST_BASELINE_Z", "0")),
      level_type=os.environ.get("PLAYTEST_LEVEL_TYPE", ""),
      view_distance=int(os.environ.get("PLAYTEST_VIEW_DISTANCE", "4")),
      simulation_distance=int(os.environ.get("PLAYTEST_SIMULATION_DISTANCE", "4")),
      max_tick_time=int(os.environ.get("PLAYTEST_MAX_TICK_TIME", "60000")),
      mute_client=truth(os.environ.get("PLAYTEST_MUTE_CLIENT"), True),
      profile_event=os.environ.get("PLAYTEST_PROFILE", "").strip(),
      profile_target=os.environ.get("PLAYTEST_PROFILE_TARGET", "client").strip().lower(),
      profiler=os.environ.get("PLAYTEST_ASPROF", "asprof").strip() or "asprof",
      profiler_alloc_interval=os.environ.get("PLAYTEST_PROFILE_ALLOC", "1m").strip() or "1m",
      profiler_cpu_sample_ms=float(os.environ.get("PLAYTEST_PROFILE_CPU_SAMPLE_MS", "10")),
      profiler_start_timeout=int(os.environ.get("PLAYTEST_PROFILE_START_TIMEOUT", "180")),
    )

  @property
  def server_pid(self) -> Path:
    return self.root / "server.pid"

  @property
  def client_pid(self) -> Path:
    return self.root / "client.pid"

  @property
  def state(self) -> Path:
    return self.root / "state"

  @property
  def logs(self) -> Path:
    return self.root / "logs"

  @property
  def inbox(self) -> Path:
    return self.root / "inbox"

  @property
  def results(self) -> Path:
    return self.root / "results"

  @property
  def serial_lock(self) -> Path:
    return self.repo / "run" / "playtest.lock"


@dataclass(frozen=True)
class WorldSpec:
  key: str
  seed: str
  level_type: str

  @staticmethod
  def from_arg(config: PlaytestConfig, raw: str = "default") -> "WorldSpec":
    candidate = Path(raw)
    if candidate.is_file():
      scenario = read_json(candidate)
      return WorldSpec(
        key=safe_key(str(scenario.get("worldKey", "default"))),
        seed=str(scenario.get("seed", "baritone-playtest")),
        level_type=config.level_type or str(scenario.get("worldPreset", "")),
      )
    return WorldSpec(safe_key(raw), os.environ.get("PLAYTEST_SEED", "baritone-playtest"), config.level_type)


class Rcon:
  def __init__(self, host: str, port: int, password: str, timeout: float):
    self.host = host
    self.port = port
    self.password = password
    self.timeout = timeout

  @staticmethod
  def packet(request_id: int, kind: int, payload: str) -> bytes:
    data = payload.encode("utf-8") + b"\0\0"
    return struct.pack("<iii", len(data) + 8, request_id, kind) + data

  @staticmethod
  def receive(sock: socket.socket) -> tuple[int, int, str]:
    header = sock.recv(4)
    if len(header) != 4:
      raise RuntimeError("rcon connection closed")
    (size,) = struct.unpack("<i", header)
    body = b""
    while len(body) < size:
      chunk = sock.recv(size - len(body))
      if not chunk:
        raise RuntimeError("rcon connection closed")
      body += chunk
    request_id, kind = struct.unpack("<ii", body[:8])
    return request_id, kind, body[8:-2].decode("utf-8", "replace")

  def command(self, command: str) -> str:
    with socket.create_connection((self.host, self.port), timeout=self.timeout) as sock:
      sock.settimeout(self.timeout)
      sock.sendall(self.packet(1, 3, self.password))
      request_id, _, _ = self.receive(sock)
      if request_id == -1:
        raise RuntimeError("rcon authentication failed")
      sock.sendall(self.packet(2, 2, command))
      _, _, text = self.receive(sock)
      return text


class Playtest:
  def __init__(self, config: PlaytestConfig):
    self.c = config
    self.current_world: WorldSpec | None = None

  def server_dir(self, world: WorldSpec) -> Path:
    return self.c.root / "server" / world.key

  def client_dir(self) -> Path:
    return self.c.root / "client"

  def rcon_password(self) -> str:
    if self.c.rcon_password:
      return self.c.rcon_password
    path = self.c.root / "rcon.password"
    if not path.exists():
      path.parent.mkdir(parents=True, exist_ok=True)
      path.write_text(secrets.token_hex(24) + "\n", encoding="utf-8")
      path.chmod(0o600)
    return path.read_text(encoding="utf-8").strip()

  def rcon(self) -> Rcon:
    return Rcon(self.c.host, self.c.rcon_port, self.rcon_password(), self.c.rcon_timeout)

  @staticmethod
  def pid_alive(path: Path) -> bool:
    try:
      os.kill(int(path.read_text().strip()), 0)
      return True
    except (FileNotFoundError, ProcessLookupError, ValueError):
      return False
    except PermissionError:
      return True

  @staticmethod
  def process_matches_root(pid: int, root: Path) -> bool:
    needle = str(root.resolve())
    proc = Path("/proc") / str(pid)
    cmdline: list[str] = []
    cwd = ""
    try:
      cmdline = [raw.decode("utf-8", "replace") for raw in (proc / "cmdline").read_bytes().split(b"\0") if raw]
    except (FileNotFoundError, ProcessLookupError, PermissionError):
      pass
    try:
      cwd = str((proc / "cwd").resolve())
    except (FileNotFoundError, ProcessLookupError, PermissionError):
      pass
    return any(path_argument_contains(arg, needle) for arg in cmdline) or cwd == needle or cwd.startswith(needle + os.sep)

  @staticmethod
  def kill_pidfile(path: Path, root: Path) -> None:
    try:
      pid = int(path.read_text().strip())
    except (FileNotFoundError, ValueError):
      return
    if not Playtest.process_matches_root(pid, root):
      path.unlink(missing_ok=True)
      return
    for killer in (
      lambda: os.killpg(pid, signal.SIGTERM),
      lambda: subprocess.run(["pkill", "-TERM", "-P", str(pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL),
      lambda: os.kill(pid, signal.SIGTERM),
    ):
      try:
        killer()
      except (ProcessLookupError, PermissionError):
        pass
    path.unlink(missing_ok=True)

  @staticmethod
  def process_roots(root: Path) -> set[int]:
    pids: set[int] = set()
    self_pid = os.getpid()
    for proc in Path("/proc").iterdir():
      if not proc.name.isdecimal():
        continue
      pid = int(proc.name)
      if pid == self_pid:
        continue
      if Playtest.process_matches_root(pid, root):
        pids.add(pid)
    return pids

  @staticmethod
  def terminate_pids(pids: set[int], *, timeout: float = 8.0) -> None:
    pids = {pid for pid in pids if pid > 1 and pid != os.getpid()}
    if not pids:
      return
    for sig in (signal.SIGTERM, signal.SIGKILL):
      for pid in sorted(pids, reverse=True):
        try:
          os.kill(pid, sig)
        except (ProcessLookupError, PermissionError):
          pass
      deadline = time.monotonic() + timeout
      while time.monotonic() < deadline:
        alive: set[int] = set()
        for pid in pids:
          try:
            os.kill(pid, 0)
            alive.add(pid)
          except ProcessLookupError:
            pass
          except PermissionError:
            alive.add(pid)
        pids = alive
        if not pids:
          return
        time.sleep(0.2)

  def stop(self) -> None:
    self.kill_pidfile(self.c.client_pid, self.c.root)
    self.kill_pidfile(self.c.server_pid, self.c.root)
    self.terminate_pids(self.process_roots(self.c.root))
    for pidfile in self.c.root.glob("*.pid"):
      pidfile.unlink(missing_ok=True)

  def stop_all(self) -> None:
    roots = {self.c.root.resolve()}
    run = self.c.repo / "run"
    if run.exists():
      for root in run.iterdir():
        if root.is_dir() and root.name.startswith("playtest"):
          roots.add(root.resolve())
      for pidfile in run.glob("playtest*/**/*.pid"):
        if pidfile.name in {"client.pid", "server.pid"}:
          roots.add(pidfile.parent.resolve())
    for root in sorted(roots):
      Playtest(replace(self.c, root=root)).stop()

  def serial(self) -> "SerialPlaytestLock":
    return SerialPlaytestLock(self.c.serial_lock)

  def prepare_client_options(self, directory: Path) -> None:
    if not self.c.mute_client:
      return
    directory.mkdir(parents=True, exist_ok=True)
    options = directory / "options.txt"
    kept: list[str] = []
    if options.exists():
      kept = [line for line in options.read_text(encoding="utf-8").splitlines() if not re.match(r"^(soundCategory_|musicToast:|musicFrequency:|showSubtitles:|directionalAudio:)", line)]
    muted = [
      "soundCategory_master:0.0",
      "soundCategory_music:0.0",
      "soundCategory_record:0.0",
      "soundCategory_weather:0.0",
      "soundCategory_block:0.0",
      "soundCategory_hostile:0.0",
      "soundCategory_neutral:0.0",
      "soundCategory_player:0.0",
      "soundCategory_ambient:0.0",
      "soundCategory_voice:0.0",
      'musicToast:"never"',
      'musicFrequency:"OFF"',
      "showSubtitles:false",
      "directionalAudio:false",
    ]
    options.write_text("\n".join([*kept, *muted, ""]), encoding="utf-8")

  def prepare_server(self, directory: Path, world: WorldSpec) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    props = {
      "accepts-transfers": "false",
      "allow-flight": "false",
      "difficulty": "peaceful",
      "enable-command-block": "false",
      "enable-query": "false",
      "enable-rcon": "true",
      "enforce-secure-profile": "false",
      "gamemode": "survival",
      "generate-structures": "true",
      "level-name": "world",
      "level-seed": world.seed,
      "max-players": "4",
      "max-tick-time": str(self.c.max_tick_time),
      "motd": "baritone-playtest",
      "online-mode": "false",
      "prevent-proxy-connections": "false",
      "pvp": "false",
      "rcon.password": self.rcon_password(),
      "rcon.port": str(self.c.rcon_port),
      "server-ip": "",
      "server-port": str(self.c.port),
      "simulation-distance": str(self.c.simulation_distance),
      "spawn-protection": "0",
      "view-distance": str(self.c.view_distance),
      "white-list": "false",
    }
    if world.level_type:
      props["level-type"] = world.level_type
    (directory / "server.properties").write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")
    write_json(
      directory / "ops.json",
      [
        {"uuid": self.c.observer_uuid, "name": self.c.observer_username, "level": 4, "bypassesPlayerLimit": False},
        {"uuid": self.c.uuid, "name": self.c.username, "level": 4, "bypassesPlayerLimit": True},
      ],
    )

  def server_ready(self, log: Path) -> bool:
    return log.exists() and "Done (" in log.read_text(encoding="utf-8", errors="replace")

  def start_server(self, world: WorldSpec) -> None:
    if self.pid_alive(self.c.server_pid):
      return
    directory = self.server_dir(world)
    self.prepare_server(directory, world)
    self.c.logs.mkdir(parents=True, exist_ok=True)
    log = self.c.logs / "server.log"
    with log.open("w", encoding="utf-8") as out:
      proc = subprocess.Popen(
        ["bash", "-lc", 'tail -f /dev/null | env GRADLE_USER_HOME="$1" ./gradlew :fabric:runPlaytestServer -PbaritonePlaytestServerDir="$2"', "playtest-server", self.c.gradle_user_home, str(directory)],
        cwd=self.c.repo,
        stdin=subprocess.DEVNULL,
        stdout=out,
        stderr=subprocess.STDOUT,
        start_new_session=True,
      )
    self.c.server_pid.write_text(f"{proc.pid}\n", encoding="utf-8")
    for _ in range(180):
      if self.server_ready(log):
        return
      if not self.pid_alive(self.c.server_pid):
        sys.stderr.write(tail(log))
        raise SystemExit(1)
      time.sleep(1)
    sys.stderr.write(tail(log))
    raise SystemExit(1)

  def start_client(self) -> None:
    if self.pid_alive(self.c.client_pid):
      return
    cdir = self.client_dir()
    self.c.inbox.mkdir(parents=True, exist_ok=True)
    self.c.results.mkdir(parents=True, exist_ok=True)
    self.c.logs.mkdir(parents=True, exist_ok=True)
    self.prepare_client_options(cdir)
    log = self.c.logs / "client.log"
    with log.open("w", encoding="utf-8") as out:
      proc = subprocess.Popen(
        [
          "xvfb-run",
          "-a",
          "-s",
          f"-screen 0 {self.c.screen}",
          "env",
          f"GRADLE_USER_HOME={self.c.gradle_user_home}",
          "./gradlew",
          ":fabric:runPlaytestClient",
          f"-PbaritonePlaytestClientDir={cdir}",
          f"-PbaritonePlaytestInbox={self.c.inbox}",
          f"-PbaritonePlaytestResults={self.c.results}",
          f"-PbaritonePlaytestServer={self.c.host}:{self.c.port}",
          f"-PbaritonePlaytestUsername={self.c.username}",
          f"-PbaritonePlaytestUuid={self.c.uuid}",
        ],
        cwd=self.c.repo,
        stdin=subprocess.DEVNULL,
        stdout=out,
        stderr=subprocess.STDOUT,
        start_new_session=True,
      )
    self.c.client_pid.write_text(f"{proc.pid}\n", encoding="utf-8")

  def find_profile_target_pid(self) -> int | None:
    if self.c.profile_target != "client":
      raise SystemExit(f"unsupported PLAYTEST_PROFILE_TARGET={self.c.profile_target!r}; only 'client' is wired")
    result_arg = f"-Dbaritone.playtest.results={self.c.results}"
    for proc in Path("/proc").iterdir():
      if not proc.name.isdecimal():
        continue
      try:
        cmdline = [raw.decode("utf-8", "replace") for raw in (proc / "cmdline").read_bytes().split(b"\0") if raw]
      except (FileNotFoundError, ProcessLookupError, PermissionError):
        continue
      if "net.fabricmc.loader.impl.launch.knot.KnotClient" in cmdline and "-Dbaritone.playtest.enabled=true" in cmdline and result_arg in cmdline:
        return int(proc.name)
    return None

  def wait_profile_target_pid(self) -> int | None:
    deadline = time.monotonic() + self.c.profiler_start_timeout
    while time.monotonic() < deadline:
      pid = self.find_profile_target_pid()
      if pid is not None:
        return pid
      if not self.pid_alive(self.c.client_pid):
        return None
      time.sleep(0.25)
    return None

  def profiler_session(self, run_dir: Path) -> "AsyncProfilerSession":
    return AsyncProfilerSession(self, run_dir)

  def server_cmd(self, command: str) -> str:
    if not command:
      raise SystemExit("server command required")
    if not self.pid_alive(self.c.server_pid):
      raise SystemExit("playtest server is not running")
    text = self.rcon().command(command)
    if text:
      print(text, end="" if text.endswith("\n") else "\n")
    return text

  def server_cmd_retry(self, command: str) -> str:
    last: Exception | None = None
    for _ in range(30):
      try:
        return self.server_cmd(command)
      except Exception as e:
        last = e
        time.sleep(1)
    if last is not None:
      raise last
    return self.server_cmd(command)

  def lock_world(self) -> None:
    for command in (
      "gamerule advance_time false",
      "gamerule advance_weather false",
      "difficulty peaceful",
      "time set noon",
      f"setworldspawn {self.c.baseline_x} {self.c.baseline_y} {self.c.baseline_z}",
    ):
      self.server_cmd_retry(command)

  def ensure_world(self, world: WorldSpec, *, client: bool) -> None:
    if self.c.state.exists() and self.c.state.read_text(encoding="utf-8").strip() != world.key:
      self.stop()
    self.c.state.parent.mkdir(parents=True, exist_ok=True)
    self.c.state.write_text(world.key + "\n", encoding="utf-8")
    self.current_world = world
    self.start_server(world)
    self.lock_world()
    if client:
      self.start_client()

  def daemon(self, world: WorldSpec) -> None:
    self.ensure_world(world, client=True)
    print(f"playtest daemon: world={world.key} seed={world.seed} server={self.c.host}:{self.c.port}")

  def server_only(self, world: WorldSpec) -> None:
    self.ensure_world(world, client=False)
    print(f"playtest server: world={world.key} seed={world.seed} server={self.c.host}:{self.c.port}")

  def ensure_python_env(self) -> Path:
    venv = self.c.root / "py" / ".venv"
    python = venv / "bin" / "python"
    if not python.exists():
      if shutil.which("uv") is None:
        raise SystemExit("uv is required for playtest Python helpers")
      subprocess.run(["uv", "venv", str(venv)], check=True, stdout=subprocess.DEVNULL)
    if subprocess.run([str(python), "-c", "import nbtlib"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode != 0:
      if shutil.which("uv") is None:
        raise SystemExit("uv is required to install nbtlib")
      subprocess.run(["uv", "pip", "install", "--python", str(python), "nbtlib"], check=True, stdout=subprocess.DEVNULL)
    return python

  def prune_inbox(self) -> None:
    self.c.inbox.mkdir(parents=True, exist_ok=True)
    for child in self.c.inbox.glob("*.json"):
      child.unlink()

  def scenario_run_timeout(self, scenario_path: Path) -> int:
    scenario = read_json(scenario_path)
    settings = scenario.get("settings") or {}
    timeout_ticks = int(scenario.get("timeoutTicks", 2400) or 2400)
    setup_commands = len(scenario.get("setupCommands") or []) + 14
    primary_ms = int(settings.get("primaryTimeoutMS", 20000) or 20000)
    failure_ms = int(settings.get("failureTimeoutMS", 30000) or 30000)
    physics_seconds = math.ceil(timeout_ticks / 20)
    calc_seconds = math.ceil(max(primary_ms, failure_ms) / 1000)
    return min(self.c.result_timeout, max(120, physics_seconds + calc_seconds + setup_commands + 90))

  def write_harness_failure(self, scenario_path: Path, run_id: str, summary_path: Path, reason: str, message: str, elapsed_seconds: int) -> None:
    scenario = read_json(scenario_path)
    result_scenario = summary_path.parent / "scenario.json"
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    if not result_scenario.exists():
      shutil.copyfile(scenario_path, result_scenario)
    start = scenario.get("start") or {}
    goal = scenario.get("goal") or {}
    write_json(
      summary_path,
      {
        "schema": 1,
        "id": scenario.get("id", run_id),
        "runId": run_id,
        "success": False,
        "terminalReason": reason,
        "harnessFailure": True,
        "harnessMessage": message,
        "elapsedTicks": 0,
        "elapsedNanos": elapsed_seconds * 1_000_000_000,
        "finishedAt": now(),
        "worldKey": scenario.get("worldKey", "default"),
        "seed": scenario.get("seed", "baritone-playtest"),
        "dimension": scenario.get("dimension", "minecraft:overworld"),
        "start": {"x": float(start.get("x", 0)), "y": float(start.get("y", 0)), "z": float(start.get("z", 0))},
        "goal": {"x": int(goal.get("x", 0)), "y": int(goal.get("y", 80)), "z": int(goal.get("z", 0))},
        "distanceToGoal": None,
        "tookDamage": False,
        "moveForwardTicks": 0,
        "jumpTicks": 0,
        "sprintTicks": 0,
        "sprintInputTicks": 0,
        "sneakTicks": 0,
        "actualModeTicks": MODE_TICKS_ZERO,
        "plannedModeTicks": MODE_TICKS_ZERO,
        "pathEvents": 0,
        "nextCalcFailures": 0,
        "lastPathEvent": None,
        "calcFailed": False,
        "sawPathing": False,
        "sawWater": False,
        "sawBoat": False,
        "telemetry": None,
        "scenarioFile": str(result_scenario),
      },
    )

  def run_scenario(self, scenario_path: Path) -> int:
    with self.serial():
      return self.run_scenario_locked(scenario_path)

  def run_scenario_locked(self, scenario_path: Path) -> int:
    if not scenario_path.is_file():
      raise SystemExit(f"No scenario: {scenario_path}")
    scenario = read_json(scenario_path)
    runner = self.with_scenario_config(scenario)
    if runner is not self:
      return runner.run_scenario_locked(scenario_path)
    self.prune_inbox()
    self.daemon(WorldSpec.from_arg(self.c, str(scenario_path)))
    base = safe_key(scenario_path.stem)
    run_id = f"{base}-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{os.getpid()}"
    target = self.c.inbox / f"{run_id}.json"
    summary = self.c.results / run_id / "summary.json"
    run_dir = summary.parent
    run_dir.mkdir(parents=True, exist_ok=True)
    budget = self.scenario_run_timeout(scenario_path)
    profiler = self.profiler_session(run_dir)
    profiler.start()
    shutil.copyfile(scenario_path, target)
    started = time.monotonic()
    deadline = started + budget
    try:
      while time.monotonic() < deadline:
        if summary.exists():
          print(summary)
          try:
            return 0 if bool(read_json(summary).get("success")) else 1
          except json.JSONDecodeError:
            return 1
        if not self.pid_alive(self.c.client_pid):
          elapsed = int(time.monotonic() - started)
          self.write_harness_failure(scenario_path, run_id, summary, "HARNESS_CLIENT_DIED", "playtest client process died before writing summary", elapsed)
          print(summary)
          sys.stderr.write(tail(self.c.logs / "client.log"))
          return 1
        time.sleep(1)
      elapsed = int(time.monotonic() - started)
      self.write_harness_failure(scenario_path, run_id, summary, "HARNESS_TIMEOUT", f"timed out waiting {budget}s for summary", elapsed)
      print(summary)
      sys.stderr.write(f"Timed out after {budget}s waiting for {summary}; restarting playtest client\n")
      sys.stderr.write(tail(self.c.logs / "client.log"))
      self.kill_pidfile(self.c.client_pid, self.c.root)
      return 1
    finally:
      profiler.stop()

  def with_scenario_config(self, scenario: dict) -> "Playtest":
    config = self.c
    if "viewDistance" in scenario and int(scenario["viewDistance"]) != config.view_distance:
      config = replace(config, view_distance=int(scenario["viewDistance"]))
    if "simulationDistance" in scenario and int(scenario["simulationDistance"]) != config.simulation_distance:
      config = replace(config, simulation_distance=int(scenario["simulationDistance"]))
    return self if config is self.c else Playtest(config)

  def run_suite(self, manifest: Path) -> int:
    if not manifest.is_file():
      raise SystemExit(f"No suite manifest: {manifest}")
    status = 0
    with self.serial():
      try:
        for raw in manifest.read_text(encoding="utf-8").splitlines():
          scenario = raw.split("#", 1)[0].strip()
          if not scenario:
            continue
          print(f"playtest suite: {scenario}")
          status = max(status, self.run_scenario_locked(Path(scenario)))
      finally:
        self.stop()
    return status

  def biome_pregen(self, world: WorldSpec, radius: int, tile_chunks: int, dimension: str = "minecraft:overworld") -> None:
    batch_tiles = int(os.environ.get("PLAYTEST_PREGEN_BATCH_TILES", "1"))
    self.server_only(world)
    min_c = -radius // 16
    max_c = radius // 16
    removals: list[str] = []
    labels: list[str] = []

    def in_dimension(command: str) -> str:
      return command if dimension == "minecraft:overworld" else f"execute in {dimension} run {command}"

    def flush() -> None:
      nonlocal removals, labels
      if not removals:
        return
      try:
        self.server_cmd_retry("save-all flush")
      except Exception:
        pass
      for command, label in zip(removals, labels, strict=True):
        try:
          self.server_cmd_retry(command)
        except Exception:
          pass
        print(f"pregenerated tile chunks {label}")
      removals, labels = [], []

    for cx in range(min_c, max_c + 1, tile_chunks):
      for cz in range(min_c, max_c + 1, tile_chunks):
        x1, z1 = cx * 16, cz * 16
        x2, z2 = ((cx + tile_chunks - 1) * 16) + 15, ((cz + tile_chunks - 1) * 16) + 15
        self.server_cmd_retry(in_dimension(f"forceload add {x1} {z1} {x2} {z2}"))
        removals.append(in_dimension(f"forceload remove {x1} {z1} {x2} {z2}"))
        labels.append(f"{cx}..{cx + tile_chunks - 1} {cz}..{cz + tile_chunks - 1}")
        if len(removals) >= batch_tiles:
          flush()
    flush()
    self.lock_world()

  def biome_scan(self, world: WorldSpec, out: Path, args: list[str]) -> int:
    python = self.ensure_python_env()
    world_dir = self.server_dir(world) / "world"
    return subprocess.run([str(python), str(self.c.repo / "scripts" / "biome_profiler.py"), "scan-world", "--world", str(world_dir), "--world-key", world.key, "--seed", world.seed, "--out", str(out), *args], cwd=self.c.repo).returncode

  def biome_aggregate(self, results: Path, out: Path, args: list[str]) -> int:
    python = self.ensure_python_env()
    return subprocess.run([str(python), str(self.c.repo / "scripts" / "biome_profiler.py"), "aggregate", "--results", str(results), "--out", str(out), *args], cwd=self.c.repo).returncode

  def biome_facts(self, world: WorldSpec, out: Path, args: list[str]) -> int:
    python = self.ensure_python_env()
    world_dir = self.server_dir(world) / "world"
    return subprocess.run([str(python), str(self.c.repo / "scripts" / "biome_profiler.py"), "export-facts", "--world", str(world_dir), "--out", str(out), *args], cwd=self.c.repo).returncode

  def default_player(self, provided: str | None) -> str:
    if provided:
      return provided
    output = self.server_cmd("list")
    names = output.split(":", 1)[1] if ":" in output else output
    players = [name.strip() for name in names.split(",") if name.strip()]
    if len(players) == 1:
      return players[0]
    raise SystemExit(f"player name required; online players: {names}")

  def stage(self, scenario_path: Path, provided_player: str | None) -> None:
    if not scenario_path.is_file():
      raise SystemExit(f"No scenario: {scenario_path}")
    self.server_only(WorldSpec.from_arg(self.c, str(scenario_path)))
    player = self.default_player(provided_player)
    if not PLAYER_RE.fullmatch(player):
      raise SystemExit(f"unsupported player name for command target: {player!r}")
    scenario = read_json(scenario_path)
    for command in stage_commands(scenario, player):
      self.server_cmd(command)

  def probe(self, args: list[str]) -> int:
    if len(args) < 3:
      raise SystemExit("usage: scripts/playtest probe [worldKey|scenario.json] <x> <y> <z> [dimension]")
    if re.fullmatch(r"-?\d+", args[0]):
      world_key = self.c.state.read_text(encoding="utf-8").strip() if self.c.state.exists() else "default"
      world = WorldSpec(safe_key(world_key), os.environ.get("PLAYTEST_SEED", "baritone-playtest"), self.c.level_type)
    else:
      world = WorldSpec.from_arg(self.c, args.pop(0))
    if len(args) < 3:
      raise SystemExit("usage: scripts/playtest probe [worldKey|scenario.json] <x> <y> <z> [dimension]")
    if self.pid_alive(self.c.server_pid):
      try:
        self.server_cmd_retry("save-all flush")
      except Exception:
        pass
    python = self.ensure_python_env()
    return subprocess.run([str(python), str(self.c.repo / "scripts" / "playtest_probe.py"), str(self.server_dir(world) / "world"), args[3] if len(args) > 3 else "minecraft:overworld", args[0], args[1], args[2]], cwd=self.c.repo).returncode


def stage_commands(scenario: dict, player: str) -> list[str]:
  sid = scenario.get("id") or "scenario"
  dimension = scenario.get("dimension", "minecraft:overworld")
  start = scenario.get("start") or {}
  goal = scenario.get("goal") or {}
  loadout = scenario.get("loadout") or []
  settings = scenario.get("settings") or {}
  selected_slot = scenario.get("selectedSlot")
  if selected_slot is None:
    selected_slot = next((item.get("slot", -1) for item in loadout if item.get("slot", -1) >= 0), 0)

  commands = [
    "gamerule advance_time false",
    "gamerule advance_weather false",
    "time set noon",
    "weather clear",
    "difficulty peaceful",
    "kill @e[type=!minecraft:player]",
    f"gamemode survival {player}",
    f"effect clear {player}",
    f"effect give {player} minecraft:instant_health 1 10 true",
  ]
  if scenario.get("saturationBoost", True):
    commands.append(f"effect give {player} minecraft:saturation 1 10 true")
  commands.append(f"clear {player}")
  commands.extend(str(command) for command in scenario.get("setupCommands") or [])
  commands.append(
    "execute in %s run tp %s %.3f %.3f %.3f %.2f %.2f"
    % (
      dimension,
      player,
      float(start.get("x", 0.0)),
      float(start.get("y", 80.0)),
      float(start.get("z", 0.0)),
      float(start.get("yaw", 0.0)),
      float(start.get("pitch", 0.0)),
    )
  )
  for item in loadout:
    item_id = item.get("item", "minecraft:air")
    count = int(item.get("count", 1))
    slot = int(item.get("slot", -1))
    commands.append(f"item replace entity {player} hotbar.{slot} with {item_id} {count}" if slot >= 0 else f"give {player} {item_id} {count}")
  commands.append(tellraw(player, [{"text": f"Staged playtest scenario {sid}.", "color": "green"}]))
  for name, value in settings.items():
    command = f"#set {name} {str(value).lower() if isinstance(value, bool) else value}"
    commands.append(clickable(player, "Baritone setting: ", command, "gray"))
  match str(goal.get("type", "block")).lower():
    case "block":
      commands.append(clickable(player, "Baritone goal: ", "#goto %d %d %d" % (int(goal.get("x", 0)), int(goal.get("y", 80)), int(goal.get("z", 0))), "gold"))
    case "xz" | "goalxz" | "pointxz":
      commands.append(clickable(player, "Baritone goal: ", "#goto %d %d" % (int(goal.get("x", 0)), int(goal.get("z", 0))), "gold"))
    case other:
      commands.append(tellraw(player, [{"text": f"Unsupported manual Baritone goal type: {other}", "color": "red"}]))
  if selected_slot is not None:
    commands.append(tellraw(player, [{"text": f"Select hotbar slot {int(selected_slot) + 1} before running if the scenario depends on held item.", "color": "yellow"}]))
  return commands


def tellraw(player: str, parts: list[dict]) -> str:
  return "tellraw " + player + " " + json.dumps(parts, separators=(",", ":"))


def clickable(player: str, prefix: str, command: str, color: str) -> str:
  return tellraw(
    player,
    [
      {"text": prefix, "color": color},
      {
        "text": command,
        "color": "aqua",
        "underlined": True,
        "clickEvent": {"action": "suggest_command", "value": command},
        "hoverEvent": {"action": "show_text", "contents": "Click to paste, then press Enter."},
      },
    ],
  )


class AsyncProfilerSession:
  def __init__(self, playtest: Playtest, run_dir: Path):
    self.playtest = playtest
    self.run_dir = run_dir
    self.event = playtest.c.profile_event
    self.pid: int | None = None
    self.active = False
    self.ever_started = False
    self.artifact_stem = safe_key(self.event.replace(",", "_")) if self.event else ""

  def start(self) -> None:
    if not self.event:
      return
    if shutil.which(self.playtest.c.profiler) is None:
      self.log("asprof not found: " + self.playtest.c.profiler)
      return
    self.pid = self.playtest.wait_profile_target_pid()
    if self.pid is None:
      self.log("target JVM not found before scenario dispatch")
      return
    command = [self.playtest.c.profiler, "start", "-e", self.event, *self.event_options(), "-o", "jfr", "-f", str(self.run_dir / f"{self.artifact_stem}.jfr"), str(self.pid)]
    result = self.run(command, "start")
    self.active = result.returncode == 0
    self.ever_started = self.active
    self.write_manifest("started" if self.active else "start_failed")

  def stop(self) -> None:
    if not self.event or self.pid is None:
      return
    if self.active:
      self.dump("flamegraph")
      self.dump("collapsed")
      self.write_baritone_flamegraph()
      self.run([self.playtest.c.profiler, "stop", str(self.pid)], "stop")
      self.active = False
    self.write_manifest("stopped")

  def dump(self, fmt: str) -> None:
    suffix = {"collapsed": "txt", "flamegraph": "html"}.get(fmt, fmt)
    self.run([self.playtest.c.profiler, "dump", "-o", fmt, "-f", str(self.run_dir / f"{self.artifact_stem}.{suffix}"), str(self.pid)], "dump-" + fmt)

  def event_options(self) -> list[str]:
    events = set(self.event.split(","))
    options: list[str] = []
    if "cpu" in events:
      options.extend(["-i", str(max(1, round(self.playtest.c.profiler_cpu_sample_ms * 1_000_000)))])
    if "alloc" in events:
      options.extend(["--alloc", self.playtest.c.profiler_alloc_interval])
    return options

  def run(self, command: list[str], label: str) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(command, cwd=self.playtest.c.repo, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    with (self.run_dir / f"{self.artifact_stem}.asprof.log").open("a", encoding="utf-8") as log:
      log.write("$ " + " ".join(command) + "\n")
      log.write(result.stdout)
      if result.stdout and not result.stdout.endswith("\n"):
        log.write("\n")
      log.write(f"[{label}] exit={result.returncode}\n")
    return result

  def write_baritone_flamegraph(self) -> None:
    collapsed = self.run_dir / f"{self.artifact_stem}.txt"
    if not collapsed.exists():
      return
    baritone_collapsed = self.run_dir / f"{self.artifact_stem}.baritone.txt"
    stacks: Counter[str] = Counter()
    for line in collapsed.read_text(encoding="utf-8", errors="replace").splitlines():
      try:
        stack, raw_count = line.rsplit(" ", 1)
        count = int(raw_count)
      except ValueError:
        continue
      frames = stack.split(";")
      for index, frame in enumerate(frames):
        if frame.startswith("baritone/") or frame.startswith("baritone."):
          stacks[";".join(frames[index:])] += count
          break
    if not stacks:
      self.log("no baritone stacks in collapsed profile")
      return
    raw_total = sum(stacks.values())
    baritone_collapsed.write_text("".join(f"{stack} {count}\n" for stack, count in sorted(stacks.items())), encoding="utf-8")
    flamegraph = shutil.which("inferno-flamegraph")
    if flamegraph is None:
      self.log("inferno-flamegraph not found; wrote " + str(baritone_collapsed))
      return
    svg = self.run_dir / f"{self.artifact_stem}.baritone.svg"
    html = self.run_dir / f"{self.artifact_stem}.baritone.html"
    summary = self.run_dir / f"{self.artifact_stem}.baritone.summary.json"
    countname, factor, precision_note = self.flamegraph_units()
    write_json(
      summary,
      {
        "event": self.event,
        "unit": countname,
        "rawCount": raw_total,
        "factor": factor,
        "estimatedTotal": raw_total * factor,
        "precision": precision_note,
        "collapsed": str(baritone_collapsed),
        "svg": str(svg),
        "html": str(html),
      },
    )
    with baritone_collapsed.open(encoding="utf-8") as stdin, svg.open("w", encoding="utf-8") as stdout:
      result = subprocess.run(
        [flamegraph, "--deterministic", "--colors", "java", "--countname", countname, "--factor", f"{factor:g}", "--title", f"Baritone {self.event} entrypoints", "-"],
        cwd=self.playtest.c.repo,
        stdin=stdin,
        stdout=stdout,
        stderr=subprocess.PIPE,
        text=True,
      )
    with (self.run_dir / f"{self.artifact_stem}.asprof.log").open("a", encoding="utf-8") as log:
      log.write(
        "$ inferno-flamegraph --deterministic --colors java --countname " + countname + " --factor " + f"{factor:g}" + " --title "
        + json.dumps(f"Baritone {self.event} entrypoints") + " - > " + str(svg) + "\n"
      )
      log.write(result.stderr)
      if result.stderr and not result.stderr.endswith("\n"):
        log.write("\n")
      log.write(f"[baritone-svg] exit={result.returncode}\n")
    if result.returncode != 0:
      svg.unlink(missing_ok=True)
      html.unlink(missing_ok=True)
      return
    self.write_svg_html(svg, html, f"Baritone {self.event} entrypoints")

  def flamegraph_units(self) -> tuple[str, float, str]:
    events = set(self.event.split(","))
    if "alloc" in events:
      return "bytes", profiler_size(self.playtest.c.profiler_alloc_interval), "allocation samples scaled by async-profiler --alloc interval"
    if "cpu" in events:
      return "ms", self.playtest.c.profiler_cpu_sample_ms, "CPU samples scaled by configured async-profiler sample interval"
    return "samples", 1, "raw async-profiler collapsed sample count"

  @staticmethod
  def write_svg_html(svg: Path, html: Path, title: str) -> None:
    match = re.search(r"<svg[^>]*\sheight=\"([0-9]+(?:\.[0-9]+)?)\"", svg.read_text(encoding="utf-8", errors="replace")[:2048])
    height = max(600, math.ceil(float(match.group(1)))) if match else 1000
    html.write_text(
      "<!doctype html>\n"
      "<meta charset=\"utf-8\">\n"
      f"<title>{title}</title>\n"
      "<style>html,body{margin:0;min-height:100%;overflow:auto;background:#fff}iframe{display:block;width:100%;border:0}</style>\n"
      f"<iframe src=\"{svg.name}\" style=\"height:{height}px\"></iframe>\n",
      encoding="utf-8",
    )

  def log(self, message: str) -> None:
    self.run_dir.mkdir(parents=True, exist_ok=True)
    with (self.run_dir / "asprof.log").open("a", encoding="utf-8") as log:
      log.write(message + "\n")

  def write_manifest(self, status: str) -> None:
    write_json(
      self.run_dir / f"{self.artifact_stem}.asprof.json",
      {
        "status": status,
        "event": self.event,
        "target": self.playtest.c.profile_target,
        "pid": self.pid,
        "everStarted": self.ever_started,
        "active": self.active,
        "artifacts": {
          "html": str(self.run_dir / f"{self.artifact_stem}.html"),
          "jfr": str(self.run_dir / f"{self.artifact_stem}.jfr"),
          "collapsed": str(self.run_dir / f"{self.artifact_stem}.txt"),
          "baritoneCollapsed": str(self.run_dir / f"{self.artifact_stem}.baritone.txt"),
          "baritoneHtml": str(self.run_dir / f"{self.artifact_stem}.baritone.html"),
          "baritoneSvg": str(self.run_dir / f"{self.artifact_stem}.baritone.svg"),
          "baritoneSummary": str(self.run_dir / f"{self.artifact_stem}.baritone.summary.json"),
          "log": str(self.run_dir / f"{self.artifact_stem}.asprof.log"),
        },
      },
    )


class SerialPlaytestLock:
  def __init__(self, path: Path):
    self.path = path
    self.file = None

  def __enter__(self):
    self.path.parent.mkdir(parents=True, exist_ok=True)
    self.file = self.path.open("a+", encoding="utf-8")
    fcntl.flock(self.file.fileno(), fcntl.LOCK_EX)
    self.file.seek(0)
    self.file.truncate()
    self.file.write(f"{os.getpid()}\n")
    self.file.flush()
    return self

  def __exit__(self, exc_type, exc, tb) -> None:
    if self.file is not None:
      fcntl.flock(self.file.fileno(), fcntl.LOCK_UN)
      self.file.close()
      self.file = None


def tail(path: Path, lines: int = 120) -> str:
  if not path.exists():
    return ""
  return "".join(path.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)[-lines:])


def main(argv: list[str] | None = None) -> int:
  argv = list(sys.argv[1:] if argv is None else argv)
  repo = Path(__file__).resolve().parents[1]
  playtest = Playtest(PlaytestConfig.from_env(repo))
  if not argv:
    usage()
    return 2
  cmd, *args = argv
  match cmd:
    case "server":
      playtest.server_only(WorldSpec.from_arg(playtest.c, args[0] if args else "default"))
      return 0
    case "stage":
      if not args:
        raise SystemExit("scenario json required")
      playtest.stage(Path(args[0]), args[1] if len(args) > 1 else None)
      return 0
    case "daemon":
      playtest.daemon(WorldSpec.from_arg(playtest.c, args[0] if args else "default"))
      return 0
    case "cmd":
      playtest.server_cmd(" ".join(args))
      return 0
    case "probe":
      return playtest.probe(args)
    case "run":
      if not args:
        raise SystemExit("scenario json required")
      return playtest.run_scenario(Path(args[0]))
    case "once":
      if not args:
        raise SystemExit("scenario json required")
      try:
        return playtest.run_scenario(Path(args[0]))
      finally:
        playtest.stop()
    case "suite":
      return playtest.run_suite(Path(args[0] if args else "scenarios/playtest/locked.txt"))
    case "biome-pregen":
      playtest.biome_pregen(
        WorldSpec.from_arg(playtest.c, args[0] if args else "biome_large"),
        int(args[1]) if len(args) > 1 else 512,
        int(args[2]) if len(args) > 2 else 8,
        args[3] if len(args) > 3 else "minecraft:overworld",
      )
      return 0
    case "biome-scan":
      if len(args) < 2:
        raise SystemExit("usage: scripts/playtest biome-scan <worldKey> <outDir> [args...]")
      return playtest.biome_scan(WorldSpec.from_arg(playtest.c, args[0]), Path(args[1]), args[2:])
    case "biome-aggregate":
      results = Path(args[0]) if len(args) >= 1 else playtest.c.results
      out = Path(args[1]) if len(args) >= 2 else playtest.c.root / "biome-profile" / "biome_traversal_priors.json"
      return playtest.biome_aggregate(results, out, args[2:] if len(args) >= 2 else args[1:])
    case "biome-facts":
      if not args:
        raise SystemExit("usage: scripts/playtest biome-facts <worldKey> [outJson] [args...]")
      out = Path(args[1]) if len(args) >= 2 else playtest.c.root / "biome-profile" / f"{safe_key(args[0])}_facts.json"
      return playtest.biome_facts(WorldSpec.from_arg(playtest.c, args[0]), out, args[2:] if len(args) >= 2 else args[1:])
    case "stop":
      playtest.stop()
      return 0
    case "stop-all":
      playtest.stop_all()
      return 0
    case _:
      usage()
      return 2


def usage() -> None:
  print(
    "usage: scripts/playtest server [worldKey|scenario.json] | stage <scenario.json> [player] | daemon [worldKey|scenario.json] | cmd <server command> | probe [worldKey|scenario.json] <x> <y> <z> [dimension] | run <scenario.json> | once <scenario.json> | suite [manifest] | biome-pregen [worldKey] [radius] [tileChunks] [dimension] | biome-scan <worldKey> <outDir> [args...] | biome-aggregate [resultsDir] [outJson] | biome-facts <worldKey> [outJson] [args...] | stop | stop-all",
    file=sys.stderr,
  )


if __name__ == "__main__":
  raise SystemExit(main())
