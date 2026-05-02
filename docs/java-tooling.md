# Java Tooling

Status: working note  
Goal: make Java work feel like Rust work with `rust-analyzer`: semantic navigation, safe rename, call hierarchy, diagnostics, and deterministic large-scale refactoring

## Local Facts

This repo is Java-only today:

- root Gradle build plus `fabric` subproject
- `java_release=25`
- `java_toolchain_version=26`
- local Gradle sees `/usr/lib/jvm/java-26-openjdk`
- shell default `java`/`javac` is currently JDK 21
- no Java LSP was installed before this pass

JDT LS has now been installed under:

```text
/home/main/.local/share/jdtls
```

The installed snapshot contains:

```text
org.eclipse.jdt.ls.core_1.58.0.202604151538.jar
```

Use the repo-local launcher:

```sh
scripts/jdtls-baritone
```

It pins:

- `JAVA_HOME=/usr/lib/jvm/java-26-openjdk`
- `GRADLE_USER_HOME=$HOME/.cache/gradle`
- JDT LS workspace data at `$HOME/.cache/jdtls/baritone`
- initialization settings that disable JDT LS Gradle/Maven import
- JavaSE-25 runtime mapping to `/usr/lib/jvm/java-26-openjdk`

Before first launch, or whenever Gradle/Unimined dependencies move, refresh the ignored Eclipse metadata:

```sh
scripts/jdtls-classpath
```

This writes `.classpath`, `.project`, and `.settings/org.eclipse.jdt.core.prefs` from Gradle’s resolved `SourceSet.compileClasspath`, including the Unimined Minecraft jar and Minecraft library jars. These files are ignored and disposable.

## Primary LSP: Eclipse JDT LS

Use Eclipse JDT LS as the primary language server.

Reasons:

- It is the canonical Java LSP implementation.
- It is backed by Eclipse JDT, LSP4J, M2Eclipse, and Buildship.
- It supports Gradle projects.
- Its README currently says it supports compiling projects from Java 1.8 through 25.
- It provides diagnostics, completion, hovers, organize imports, type search, code actions, outline, navigation, references, semantic highlighting, call hierarchy, type hierarchy, and formatting.
- It only requires Java 21 to run, but we launch it on Java 26 to match this repo’s toolchain.

Sources:

- https://github.com/eclipse-jdtls/eclipse.jdt.ls
- https://projects.eclipse.org/projects/eclipse.jdt.ls/governance

Do not prefer small javac-only language servers for this repo. They may be elegant, but JDT LS has the Gradle/import/refactoring surface we need.

## LSP MCP

The `lsp` MCP can now bind to JDT LS.

Working binding:

```json
{
  "backend": "jdtls",
  "command": [
    "/home/main/programming/contrib/baritone/scripts/jdtls-baritone",
    "-data",
    "{cache_dir}/jdtls/{workspace_slug}"
  ],
  "cwd": "{workspace_root}",
  "env": {
    "JAVA_HOME": "/usr/lib/jvm/java-26-openjdk",
    "GRADLE_USER_HOME": "/home/main/.cache/gradle",
    "JDTLS_HOME": "/home/main/.local/share/jdtls"
  }
}
```

Resolved tokens for this repo:

```text
cwd=/home/main/programming/contrib/baritone
data=/home/main/.cache/jdtls/baritone
```

Useful exposed calls:

```text
definition(file, line, col)
references(file, line, col)
rename_symbol(file, line, col, new_name)
diagnostics()
hover(file, line, col)
advanced_lsp_request(...)
```

Smoke-tested successfully:

```text
definition
references
hover
textDocument/documentSymbol
textDocument/prepareRename
textDocument/completion
per-file diagnostics on Minecraft-dependent files
```

Current caveat: workspace-wide diagnostics can still be expensive through the MCP and may time out while JDT LS validates the whole tree. Prefer per-file diagnostics during edits. The former thousands of bogus `net.minecraft`/`org.slf4j` unresolved diagnostics were a Buildship/Unimined classpath failure; use `scripts/jdtls-baritone`, not raw `jdtls`, so the generated Eclipse classpath is authoritative.

## Refactoring Stack

Use three tiers.

### Tier 1: JDT LS

Best for:

- rename
- references
- definitions
- call hierarchy
- organize imports
- local code actions
- semantic diagnostics

This is the daily driver.

### Tier 2: OpenRewrite

Best for deterministic repo-wide source transformations.

OpenRewrite’s Gradle plugin is designed to parse and refactor Gradle Java projects, and the official docs describe applying it at the root of a multi-project Gradle build.

Sources:

- https://docs.openrewrite.org/reference/gradle-plugin-configuration
- https://github.com/openrewrite/rewrite

Use it for broad migrations only after the recipe is explicit. Do not keep the plugin permanently applied unless it proves low-friction with Unimined/Fabric.

### Tier 3: Spoon

Best for custom Java analyses/transforms when OpenRewrite’s recipe model is too constraining.

Spoon’s site says it supports modern Java versions up to Java 25 and provides an AST for analysis and transformation.

Source:

- https://spoon.gforge.inria.fr/

Use Spoon for bespoke audits like “find every place movement legality is represented as a sentinel double” or “extract all coordinate key packing call sites and classify collision risk.”

## Static Analysis

The build remains the oracle:

```sh
GRADLE_USER_HOME=/home/main/.cache/gradle ./gradlew :compileJava
GRADLE_USER_HOME=/home/main/.cache/gradle ./gradlew :fabric:build
```

Candidate analyzers:

- Error Prone: valuable for bug patterns, but must be tested against Java 25/26, Gradle 9.4, and Unimined.
- SpotBugs: bytecode-level, likely useful for low-risk defect finding; not a style oracle.
- PMD: useful only if rules are curated aggressively; default rulepacks are noise.
- `jdeps`: dependency and JDK-internal API audits.
- `javap`: bytecode inspection for hot-path questions.

Do not accept a linter merely because it emits warnings. Warnings are useful only if they sharpen runtime correctness, strictness, or maintainability of invariants.

## Profiling And Runtime Tools

Already available from the JDK:

```text
jcmd
jfr
jdeps
javap
jmap
jstack
jshell
```

Good next installs:

- `async-profiler` or `asprof`: CPU/allocation/flamegraph profiling with less ceremony than JFR for hot loops.
- VisualVM: Arch package exists as `visualvm`; useful for heap and live inspection, but less agent-friendly.
- JDK Mission Control: ideal JFR UI if available from package/AUR/manual install.

For Baritone specifically, in-game microprofiles remain first-class. External profilers should answer JVM-level questions; path profiles should answer domain questions.

## Formatting

Repo style currently favors:

- 2-space indentation
- 200-column line width
- token efficiency over ornamental line breaks

`clang-format` is installed and already useful for Java files. JDT LS can format, but formatter integration should not be allowed to fight the 2-space/200-column policy.

If JDT LS formatting is enabled through an editor/MCP later, feed it an Eclipse formatter profile matching the repo policy.

## Optional Tool Installs

Arch packages visible locally:

```text
extra/ctags
extra/gradle
extra/visualvm
```

Potential manual/home installs:

```text
async-profiler
JDK Mission Control
OpenRewrite CLI or init-script recipes
Spoon one-off Gradle/tool scripts
```

System Gradle is unnecessary because the wrapper works. Universal ctags is only a fallback if LSP is unavailable.

## Recommended Order

1. Fix JDT LS Gradle/Unimined classpath import in the `lsp` MCP binding path.
2. Add selected-movement path profile histograms before more pathing work.
3. Add a tiny `java-symbol` smoke test that asks JDT LS for definition/references in this repo.
4. Add OpenRewrite/Spoon only when a concrete large-scale transform appears.
5. Add async-profiler/JFR workflow when JVM-level profiling becomes the limiting signal.
