---
paths:
  - "common/src/main/java/dev/incusspawn/util/BuildOutput.java"
  - "common/src/main/java/dev/incusspawn/util/Output.java"
  - "common/src/main/java/dev/incusspawn/util/TerminalOutput.java"
  - "common/src/main/java/dev/incusspawn/util/CopyingOutput.java"
  - "common/src/main/java/dev/incusspawn/util/TerminalProgress.java"
  - "common/src/main/java/dev/incusspawn/tool/**"
  - "cli/src/main/java/dev/incusspawn/command/BuildProgress.java"
  - "cli/src/main/java/dev/incusspawn/command/RepoCloner.java"
---

# Terminal Output

All multi-step lifecycle commands use `BuildOutput` (`common/.../util/BuildOutput.java`) for terminal output formatting -- not just build/branch but also `vm` (start/stop/resize), `destroy`, `update-all`, `update-base`, `project`, and the shared `VmManager`.
`isx init`'s interactive first-run flow is the one exception (its own style).
This centralizes ANSI constants and step patterns -- individual commands should not define their own.
`BuildOutput`'s printing methods delegate to the bound `Output` (#1703); the printing itself, with the indent depth and the live step, lives in `TerminalOutput`.
A change to how a line looks goes there; a new kind of message is a method on `Output`, with a default for outputs that are not terminals.
Key helpers: `section()` for a block of work at column 0, `header()` (bold bullet) / `buildHeader()` / `branchHeader()`, `group(title, detail)` (try-with-resources; `▸ Title  detail`, children one level deeper, a blank line on close -- used for Packages, Tools, Skills, Repositories) with `list()` for a wrapping comma-separated list, `step()` for complete lines, `ok()` for a `✓` result line, `stepStart()`/`stepDone()`/`stepDone(detail)`/`stepBreak()` for slow operations, `stepProgress(detail)` for the live phase, `stepNote()`/`stepWarn()` for detail one level under the step above, `note()`, `warn()` (both warnings end with a blank line, which a closing group does not repeat), `warnBanner()`, `success()`.

**Clean off a terminal (#1037).**
Every escape `BuildOutput` writes goes through `styled()`/`ansi()`, which is `TerminalProgress.isAnsiTerminal()`: false without a console, for `TERM=dumb`, and under a non-empty `NO_COLOR`.
`warn`, `stepWarn`, `note` and `warnBanner` write to stderr; steps, headers, groups and `success` to stdout, so a piped action command's stdout holds what it did, not diagnostics.
A line that *is* the command's result (a dry-run's "Would delete ...", pool usage, the current base image, "already installed") is a `step()`, never a `note()`.
Styled text anywhere, a command included, goes through the public `BuildOutput.styled(style, text)` with its colour constants; only paths that draw on an ANSI terminal alone (the live step, `TerminalProgress` line formatters, the shell status bar) append them directly.
`TerminalLink.link()` asks the same gate (`BuildOutput.ansi()`) and prints the bare URL off a terminal.
An AI answer never reaches the terminal raw (#1413): `TerminalLink.plainText` turns what `OutputFormat.isControl` names into spaces, line feeds and tabs apart.
`TerminalLink.parseSegments` and `linkify` pass the text around a link through it, and `isx ask` prints a chunk through it off a terminal.
An AI request's error goes through `OutputFormat.oneLine`: `AiHelpClient` passes a provider's text through it, and AI Help every error it shows.
A build step's label quotes definition text too (#1610): `RepoCloner.repoDisplayName`, `BuildProgress`'s step runners (`runWithSpinner`, `runDnf`, `runLiveStep`) and its shared renderers (`formatStepLine`, with the dim context, and `plainStepLine`) pass it through `oneLine`.
So step lines and output headers show a repository's URL or a COPR name as text.
Output captured from a guest command is someone else's text too (#1588).
`BuildOutput.guestText` is how such output is shown: complete control strings (OSC, DCS, APC and the like), control sequences and other escapes dropped, each line's last text a carriage return left, then `TerminalLink.plainText`.
Its one pattern ends a control string at the next escape or introducer, as a terminal does, so a guest's long line costs linear time, never a stall.
A build step's `StepProgress` cleans its detail and log through it as it is made, the detail also through `OutputFormat.oneLine`, since a prime is code the repository controls.
So the live line, the plain failure line on stdout, the printed log and the saved failure report all show it as text, and the failure line is the last one with text left.
`Container.runQuiet`, `runAsUserQuiet` and `NpmGlobalInstall` print a failed command's output through it too, since a tool's `run:` step or an npm package's install script decides that output.
`VmAgentFailure` quotes a VM's console lines through it too, since whatever runs in the guest can write its console.
`AnsiEscapeGateTest` fails on any raw CSI/OSC literal (`\033[`, `\033]`) whose line lacks a `// raw ANSI: <why>` comment, apart from the gates, `TerminalProgress` and `ShellStatusBar` (#1082).
`RawOutputGateTest` fails on raw stream use or replacement (`System.setOut`/`setErr`), console or `printStackTrace()` use and on a print literal that starts with spaces, outside the gate classes (#1703).
Older offenders are counted per file in `cli/src/test/resources/raw-output-baseline.txt`: a file may not exceed its count, and a count it no longer reaches must be lowered.
Escapes follow stdout: `System.console()` never looks at stderr, so `2>log` alone keeps colour (documented, not fixable without native code).
A warning's closing blank line goes to stdout, so stdout's flow keeps its layout when stderr is redirected.
A styled string is never a `static final` (it would be decided when the native image is built): `InitCommand`'s borders are methods for that reason.

**Live steps.**
On an ANSI terminal `stepStart` animates a spinner (plus the `stepProgress` detail) until `stepDone`, which rewrites it as `✓ label` (elapsed time appended past 2s); `stepBreak` gives `✗ label`.
Without a terminal the same calls print `label... done.`.
A step must cover *all* of its work -- e.g. `ToolVerifier` gives each `verify` its own step and reports it only after the command returns, since a slow verify after "done" looks like a hang.
While a step is live, `System.out`/`System.err` are wrapped so any other write first freezes the line (`… label`) and stops the spinner; `BaseCommand` calls `abandonStep()` after every command so an exception cannot leave the wrap installed.
Anything that hands the terminal to a child directly (inherited IO, a `sudo` that may prompt on the tty) must call `BuildOutput.releaseTerminal()` first -- the wrap cannot see that output.
Host commands run inside a step should capture output rather than inherit it.
`TerminalProgress`-based spinners (dnf, base-image download, repo clones) render at `BuildOutput.indent()` and finish with the same `✓`/`✗` glyphs (`BuildOutput.CHECK`/`CROSS`).
Headers live in command classes; shared helpers like `VmManager` emit only steps so they nest correctly under whichever header the caller prints.
See DESIGN.md "Terminal Output Visual Language" for the full spec.
