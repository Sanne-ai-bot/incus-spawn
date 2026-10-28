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
`isx init` keeps its own frames (the setup banner, the numbered step boxes, the closing box), which `InitCommand` lays out and `BuildOutput.frame` draws as given (a kind of its own: not a result, nothing off a terminal); what it reports between them is steps, warnings and errors like any command's (#1703).
Its questions are shown by the `Prompts` that reads their answers (`show`, `ask`, and `say` for a line said only because one is asked), which the terminal's shows through `BuildOutput.prompt` at the step indent; `BaseCommand.askYesNo` asks a yes/no question that way.
Every question in `cli/command` is asked through `Prompts` (`askYesNo` for yes or no), `isx update-base`'s choice and `isx run`'s wait included; the old `askConfirmation` overloads, which printed the question as the caller wrote it, are gone.
This centralizes ANSI constants and step patterns -- individual commands should not define their own.
`BuildOutput`'s printing methods delegate to the bound `Output` (#1703); the printing itself, with the indent depth and the live step, lives in `TerminalOutput`.
A change to how a line looks goes there; a new kind of message is a method on `Output`, with a default for outputs that are not terminals.
Key helpers: `section()` for a block of work at column 0, `header()` (bold bullet) / `buildHeader()` / `branchHeader()`, `group(title, detail)` (try-with-resources; `▸ Title  detail`, children one level deeper, a blank line on close -- used for Packages, Tools, Skills, Repositories) with `list()` for a wrapping comma-separated list, `step()` for complete lines, `ok()` for a `✓` result line, `stepStart()`/`stepDone()`/`stepDone(detail)`/`stepBreak()` for slow operations, `stepProgress(detail)` for the live phase, `stepNote()`/`stepWarn()` for detail one level under the step above, `note()`, `warn()` (both warnings end with a blank line, which a closing group does not repeat), `error()` (why a command failed: red `Error:` on stderr, no blank line), `warnBanner()`, `success()`.

**Clean off a terminal (#1037).**
Every escape `BuildOutput` writes goes through `styled()`/`ansi()`, which is `TerminalProgress.isAnsiTerminal()`: false without a console, for `TERM=dumb`, and under a non-empty `NO_COLOR`.
`warn`, `stepWarn`, `note` and `warnBanner` write to stderr; steps, headers, groups and `success` to stdout, so a piped action command's stdout holds what it did, not diagnostics.
A line that *is* the command's result (a dry-run's "Would delete ...", pool usage, the current base image, "already installed") is a `step()`, never a `note()`.
A query's answer (a status report, a table, `OutputFormat`'s records) is printed as given to `BuildOutput.results()`: the terminal's stdout, nothing while the TUI draws.
Printers take it as a `PrintStream`, and tests hand them a `RecordingOutput`'s `results()`, which records each line as a `RESULT`.
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
So the live line, the plain failure line, the log's warning and the saved failure report all show it as text, and the failure line is the last one with text left.
Off an ANSI terminal a one-task step that is done is a step (`BuildProgress.reportPlain`), and a failed one is one indented warning line with its detail, the end of its log under it (`finishSpinner`).
On an ANSI terminal the failed step's line is drawn, and its log is a warning under it; either way the exception carries all of the log for the failure report (#1703).
A failed prime also names its step and command (#1742), its carriage returns turned to spaces before `guestText`, so one cannot draw over the step's name: the failure line reads ``prime `<command>` failed`` with the command's first line cut to 40 characters, so the reason after it survives the terminal's width.
Its output's heading reads `prime output`, and the whole command leads that output as a shell shows it (`$ `, then `> ` per continued line), so a report trimmed to the last 100 lines keeps it.
`Container.runQuiet`, `runAsUserQuiet` and `NpmGlobalInstall` show a failed command's output through it too, since a tool's `run:` step or an npm package's install script decides that output.
They report it as one warning, its lines as details (`BuildOutput.guestLines`), to the container's `Output`: the caller's when it made the `Container` with one, else the bound output (#1703).
A long output shows its last 40 lines (`BuildOutput.lastOf`), after one saying how many came before.
The exception thrown carries all of it (`Container.failed`, `withStepOutput`), so the build's failure report keeps what the warning leaves out.
`VmAgentFailure` quotes a VM's console lines through it too, since whatever runs in the guest can write its console.
`AnsiEscapeGateTest` fails on any raw CSI/OSC literal (`\033[`, `\033]`) whose line lacks a `// raw ANSI: <why>` comment, apart from the gates, `TerminalProgress` and `ShellStatusBar` (#1082).
`RawOutputGateTest` fails on raw stream use or replacement (`System.setOut`/`setErr`), console or `printStackTrace()` use and on a print literal that starts with spaces, outside the gate classes (#1703).
Code that hands the terminal over or draws on it whole (an interactive guest command's inherited output, an interactive shell's raw bytes and its status bar, `TerminalProgress`, `HostRepoRefresh`'s clone question, `isx git-remote-helper`'s protocol streams) takes the process's streams and console from the gate `TerminalStreams` (`common/util`), so a stray print in the same file is still caught.
A window title is no such thing: `InteractiveShell` sets and resets it through `BuildOutput.windowTitle`; its reconnect notice is one `warn` per lost connection, each retry after it a `note`, and a session that stayed up past the longest wait (10 s) and dropped counts its retries afresh, while one that drops at once is a retry.
`TerminalStreams.terminal()` also holds the one copy of the JDK 22 to 24 check that stdin and stdout are a terminal, which `Prompts.terminal()` calls, and `BuildInterrupt.atTerminal()` through it.
Only the files `RawOutputGateTest.MAY_USE_TERMINAL_STREAMS` names may call it, a method reference included, and an entry that no longer calls it fails.
Older offenders are counted per file in `cli/src/test/resources/raw-output-baseline.txt`: a file may not exceed its count, and a count it no longer reaches must be lowered.
Tests assert on `RecordingOutput`'s typed events (common test-jar) instead of swapping streams; `StreamSwapGateTest` allows a swap only in the test classes its `MAY_SWAP` map lists, for output still printed raw or for the streams themselves (`BuildOutputTest`, `OutputBindingTest`, the pre-TUI hold).
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
Off an ANSI terminal `RepoCloner`'s parallel block reports each repository as it ends, from its state (`TerminalProgress.run` with `plainDone`): a step when ready, a warning when it failed.
On any terminal the start of a failed repository's command and the end of its log are one warning (#1703).
Headers live in command classes; shared helpers like `VmManager` emit only steps so they nest correctly under whichever header the caller prints.
A build that fails says why as an error, its diagnostics as notes as they are found (a later probe may hang), and where the failure report was saved, with the container promoted for inspection, as warnings, so the TUI's log keeps where to look (`BuildCommand.reportBuildFailure`, #1703).
See DESIGN.md "Terminal Output Visual Language" for the full spec.
