# AGENTS.md

## Project

This is `baritone-1337`: a Fabric-only, agent-maintained fork of upstream [Baritone](https://github.com/cabaletta/baritone).

Current target: Minecraft `26.1.2`, Fabric Loader `0.19.2`, Java toolchain `26`, Java release bytecode `26`.

No Forge, NeoForge, GitLab, JitPack, Docker, GitHub Actions, issue templates, PR templates, or compatibility theater. Do not reintroduce service scaffolding unless it directly builds or ships this fork.

## Doctrine

Use the newest Java features aggressively. Prefer records, sealed types, pattern matching, switch expressions, modern concurrency primitives, and any other current language/library machinery that tightens the implementation.

Optimize first for runtime performance, then for token efficiency. All other concerns are subordinate.

“Simplicity” and “understandable to humans” are not goals. If a stronger abstraction, denser representation, or more sophisticated mechanism makes the code faster, smaller, stricter, or more exact, use it.

No backward compatibility by default. If a type, shim, API seam, command, file, or build surface no longer earns its keep, delete it.

## Build Oracle

Use the Gradle wrapper, not system Gradle:

```sh
GRADLE_USER_HOME=/home/main/.cache/gradle ./gradlew test
GRADLE_USER_HOME=/home/main/.cache/gradle ./gradlew :fabric:build
```

Fabric artifacts land in `dist/`; the standalone Fabric jar is the normal playtest artifact.

## Local Instances

`vims` is the local MultiMC instance used for ordinary manual Minecraft testing, not an SSH host. Its mod directory is:

```sh
/home/main/.local/share/multimc/instances/vims/.minecraft/mods
```

To push the latest build there, copy the standalone Fabric artifact:

```sh
install -Dm644 dist/baritone-1337-standalone-fabric-1.17.0+26.1.2.jar /home/main/.local/share/multimc/instances/vims/.minecraft/mods/baritone-1337-standalone-fabric-1.17.0+26.1.2.jar
```

## Formatting

Formatting policy is 2-space indentation, 200-column width, minimum gratuitous line wrapping, and no trailing whitespace. Token efficiency beats ornamental verticality.

Use Spotless as the canonical formatter:

```sh
GRADLE_USER_HOME=/home/main/.cache/gradle ./gradlew spotlessApply
GRADLE_USER_HOME=/home/main/.cache/gradle ./gradlew spotlessCheck
```

The authoritative Java formatter profile is `eclipse-compact.xml`; do not hand-format around it.

## Notes

Long-lived public prose belongs only in `README.md`, `LICENSE`, this file, and `TESTING.md`. Speculative design and planning notes belong in ignored `notes/`.

## Testing Polygons

The canonical live-fire policy and polygon atlas live in `TESTING.md`.

## Java Tooling

Use Eclipse JDT LS as the primary Java semantic engine through the `lsp` MCP. Prefer semantic rename, references, definition, call hierarchy, hover, code actions, and per-file diagnostics over raw text surgery when changing Java.

Launch/bind JDT LS with the system `jdtls` and this environment:

```json
{
  "backend": "jdtls",
  "command": ["jdtls", "-data", "{cache_dir}/jdtls/{workspace_slug}"],
  "cwd": "{workspace_root}",
  "env": {
    "JAVA_HOME": "/usr/lib/jvm/java-26-openjdk",
    "GRADLE_USER_HOME": "/home/main/.cache/gradle",
    "JDTLS_HOME": "/home/main/.local/share/jdtls"
  }
}
```

Workspace-wide diagnostics can be expensive; prefer per-file diagnostics while editing. If JDT LS/Buildship loses the Minecraft/Unimined classpath, trust Gradle first and repair the LSP import path rather than accepting bogus unresolved-symbol floods.

Use OpenRewrite for explicit repo-wide Java migrations. Use Spoon for bespoke Java AST audits/transforms when OpenRewrite is too rigid. Do not add either permanently unless the recipe/tooling earns its keep.

JDK tools already available and worth using: `jcmd`, `jfr`, `jdeps`, `javap`, `jmap`, `jstack`, `jshell`. For JVM-level profiling, prefer async-profiler/JFR; for pathing-domain evidence, prefer the unified in-game profiler (`#profile`).
