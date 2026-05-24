# TUNING.md

## Golden Default

There is one blessed tuning truth: the **Golden Default**. Production behavior, live-fire baselines, playtest suites, and tuning grinds start from it unless an experiment explicitly declares a trial-local override.

Accepted wins are promoted into the Golden Default promptly. They do not remain entombed in run directories, copied into harness scripts, baked into scenario JSON, or smeared across Java literals. Multiple tuning truths are rot.

The Golden Default is one logical object and one physical file. Loader-specific tuning classes are projections of this object, never roots.

Current canonical file:

- `config/baritone/golden-tuning.toml`

## Law

Every accepted tuning improvement must be swept into the canonical file before it becomes a new baseline. The commit should preserve enough provenance to identify the run, trial, suite, and scoring rule that justified the change.

Every tuning grind starts from the Golden Default. Trial-local profiles are disposable mutations of that baseline, not alternative defaults.

Every live-fire summary must stamp the tuning profile and digest when a tunable surface is involved. A digest mismatch invalidates the trial as tuning evidence.

Built-in constants are fallback bootstraps, not secret defaults. If a value is empirically tuned, it belongs in the Golden Default surface or in a loader that reads that surface. If a value is analytic, it should not masquerade as a tunable.

Scenario-local tuning is permitted only for controlled experiments. It must not be used to define accepted behavior.

## Harness Geometry

The standard live-fire harness view distance is **8 chunks**. This is the baseline visibility contract for pedestrian, farfield, and long-range navigation tuning unless a scenario or experiment explicitly declares another value.

View distance changes the problem. A run at view distance 4 is not comparable to a standard baseline; a Nether 32 experiment is likewise a different operating regime and must be labeled as such in the run notes. Overrides are allowed, but they are evidence for the named override, not for the Golden Default baseline.

The playtest harness default is therefore `PLAYTEST_VIEW_DISTANCE=8`. Scenario JSON may use `viewDistance` only when the visibility radius is itself under test. Client render distance inherits the harness view distance unless `PLAYTEST_CLIENT_RENDER_DISTANCE` is explicitly set.

Simulation distance is a separate ticking/entity budget, not the pathing visibility contract. Do not treat it as a substitute for view distance.

## Long Grinds

Multi-hour tuning and live-fire cooks run under **user systemd units**. `tmux`, ad-hoc `nohup`, and naked background jobs are not the canonical substrate; they are too easy to strand, double-launch, or accidentally kill while the playtest harness rotates server/client processes.

Use `systemd-run --user` with explicit working directory, explicit venv Python, and append-only log sinks:

```sh
study=nether-a-event-core-$(date -u +%Y%m%dT%H%M%SZ)
root=/home/main/programming/contrib/baritone
log=$root/run/tune/logs/$study.log
unit=baritone-$study
mkdir -p "$root/run/tune/logs"
systemd-run --user --unit="$unit-launcher" --collect --pipe \
  /usr/bin/systemd-run --user --unit="$unit" --collect --working-directory="$root" \
    --setenv=GRADLE_USER_HOME=/home/main/.cache/gradle \
    --setenv=PYTHONUNBUFFERED=1 \
    --setenv=VIRTUAL_ENV=$root/.venv \
    --setenv=PLAYTEST_PORT=25665 \
    --setenv=PLAYTEST_RCON_PORT=25675 \
    --setenv=PATH=$root/.venv/bin:/usr/local/sbin:/usr/local/bin:/usr/bin:/home/main/.local/bin:/home/main/bin \
    --property=StandardOutput=append:$log \
    --property=StandardError=append:$log \
    "$root/.venv/bin/python3" "$root/scripts/tune_pedestrian_macro" \
    --scenario "$root/scenarios/playtest/nether_thisway_500_a.json" \
    --trials 160 \
    --timeout-hours 8 \
    --study-name "$study" \
    --root "$root/run/tune/pedestrian_event_core" \
    --playtest-root "$root/run/playtest-pedestrian-event-core"
```

The log is the primary observability surface: `tail -f "$log"`. `journalctl --user -u "$unit" --no-pager` is useful when the user bus is healthy, but the run must remain diagnosable from files alone.

The nested `systemd-run --pipe /usr/bin/systemd-run ...` is intentional. Some sandboxed shells fail to connect to the user manager through systemd's private local transport; `--pipe` forces a healthy D-Bus launch of a tiny launcher unit, and that launcher starts the real detached unit from inside the user manager.

Long grinds should use a dedicated port pair unless port occupancy itself is being tested. The server launch stamp includes both ports; changing them forces a clean server restart instead of reusing a stale daemon.

The playtest process reaper protects its current ancestor chain. That is deliberate: long-running tuners often mention the playtest root in their command line, and the child harness must never cull the parent study while cleaning stale Minecraft processes.

## Operator Config

Ordinary `#config` is an operator override layer, not a tuning database. A live instance may override taste, UI, build/mine preferences, geofences, and deliberate emergency switches, but it must not accidentally shadow the Golden Default with stale empirical constants.

Golden-owned values should therefore be absent from `settings.txt` during production play. Longer term, empirical tuning surfaces should be moved out of the ordinary user-config serialization path or treated as Golden-profile values whose defaults are rebased before `#set save` decides what is modified.

## Orthogonality

Tune only an orthogonal basis.

Every exposed tunable must correspond to one independent empirical quantity, policy budget, or feature gate. If a value is derivable from another tunable, from an analytic invariant, or from a monotone relationship, it is not a tunable. It is a formula.

Weak, redundant, smeared, or reward-hacking knobs poison search. A tuning surface must therefore be factorized before it is ground:

- one causal effect, one knob;
- shared scale plus dimensionless multipliers instead of many unrelated absolute costs;
- probabilities represented as constrained mixtures, not free values that can sum to nonsense;
- ordered values represented by bases/deltas or formulas, not independently sampled constants;
- execution safety, exact collision truth, and route validity kept out of empirical cost knobs;
- feature gates reserved for ablation and deployment, not intermixed with continuous policy search.

A new tuning run should start by deleting knobs. Add a knob only when the remaining basis cannot express a real observed degree of freedom.

## Promotion Ritual

When a candidate wins:

1. Confirm it clears the relevant acceptance suite from a clean Golden Default start.
2. Prefer a robust basin over a freak optimum: the chosen settings should sit inside a neighborhood of near-best runs, not balance on a knife edge.
3. Round empirical floats to four decimal places unless the tuning surface explicitly needs finer precision.
4. Promote the values into the canonical file.
5. Re-run the acceptance suite against the promoted file, not the trial-local file.
6. Commit the shard update with the result path or trial identifier in the message or file comment.

## New Surfaces

A new tunable surface must define:

- its canonical Golden file section;
- which regime or mode consumes it;
- which harnesses stamp its digest;
- which values are genuinely empirical rather than analytic;
- which values are monotone, ordered, or otherwise constrained and therefore must be represented without invalid combinations.

If two regimes need different values, split the surface by regime. If a value is shared only by accident, make that fact impossible to confuse.
