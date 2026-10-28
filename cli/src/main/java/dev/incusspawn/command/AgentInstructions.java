package dev.incusspawn.command;

import dev.incusspawn.config.AgentContextGenerator;
import dev.incusspawn.incus.Container;

import java.util.Collection;
import java.util.List;

/**
 * Stores the build's generated agent instructions once and puts them where each installed
 * agent reads them (#1788), in one guest exec.
 *
 * <p>The one copy is {@link #STORE}, root-owned and 0644. A location outside the agent user's
 * home is a hardlink to it, so every agent reads the same inode; one inside the home (Gemini's
 * {@code ~/.gemini/GEMINI.md}) is a copy owned by agentuser, since all links share one owner and
 * mode, and a root-owned file there would be one the user cannot edit. A link that cannot be
 * made, as when a mount puts the location on another filesystem, falls back to a copy.
 *
 * <p>The store is replaced by a rename and every location is linked again on each build, so a
 * location a tool replaced with a file of its own is relinked rather than left stale. A location
 * no installed tool declares any more is removed when isx wrote it: a link to the store, or a
 * file that starts with the generator's heading, which also covers the copies older builds left
 * at {@link #LEGACY} (the managed policy was written whether or not claude was installed). A file
 * a template put there itself is kept.
 */
final class AgentInstructions {

    /** The one stored copy of the generated instructions. */
    static final String STORE = "/etc/isx/agent-instructions.md";

    /** The locations the last build linked or copied the store to, one per line. */
    static final String RECORD = "/etc/isx/agent-instructions.paths";

    /** Locations builds wrote before {@link #RECORD} existed: what they wrote, so never changed. */
    static final List<String> LEGACY = List.of("/etc/claude-code/CLAUDE.md", "/AGENTS.md");

    private AgentInstructions() {
    }

    /** Store {@code content} and place it at every declared location, cleaning up stale ones. */
    static void install(Container container, String content, Collection<String> declared) {
        container.sh(script("", content, declared))
                .assertSuccess("Failed to write the agent instructions");
    }

    /**
     * The script {@link #install} runs, with every path under {@code root} ({@code ""} in a
     * guest), so a test can run it against a scratch directory. Starts with the store's heredoc.
     */
    static String script(String root, String content, Collection<String> declared) {
        var store = Container.shellQuote(root + STORE);
        var sb = new StringBuilder();
        sb.append(Container.heredoc("set -e; mkdir -p \"$(dirname " + store + ")\"; cat > " + store + ".new",
                content.strip())).append('\n');
        sb.append("store=").append(store).append('\n');
        sb.append("r=").append(Container.shellQuote(root)).append('\n');
        sb.append("heading=").append(Container.shellQuote(AgentContextGenerator.HEADING)).append('\n');
        sb.append("""
                isx_stale() {
                  { [ -f "$r$1" ] || [ -L "$r$1" ]; } || return 0
                  if [ "$r$1" -ef "$store" ] || [ "$(head -n 1 "$r$1" 2>/dev/null)" = "$heading" ]; then rm -f "$r$1"; fi
                }
                isx_place() {
                  if [ -d "$r$1" ]; then echo "$1 is a directory, not the agent's file" >&2; return 1; fi
                  mkdir -p "$(dirname "$r$1")"
                }
                isx_link() {
                  isx_place "$1"
                  ln -f "$store" "$r$1" 2>/dev/null || { rm -f "$r$1" && cp "$store" "$r$1"; }
                }
                isx_copy() {
                  isx_place "$1"
                  rm -f "$r$1" && cp "$store" "$r$1" && chown agentuser:agentuser "$r$1"
                }
                """);

        // Stale locations first, while a link isx made still names the store's inode.
        var kept = new StringBuilder("''");
        for (var path : declared) {
            kept.append('|').append(Container.shellQuote(path));
        }
        sb.append("{ cat \"$r").append(RECORD).append("\" 2>/dev/null || true; printf '%s\\n'");
        for (var path : LEGACY) {
            sb.append(' ').append(Container.shellQuote(path));
        }
        sb.append("; } | while IFS= read -r p; do\n")
                .append("  case \"$p\" in ").append(kept).append(") ;; *) isx_stale \"$p\" ;; esac\n")
                .append("done\n");

        // Recorded before any is placed, so a build that fails partway still leaves the next
        // one a record of every location it may have touched.
        sb.append(Container.heredoc("cat > \"$r" + RECORD + "\"", String.join("\n", declared))).append('\n');

        // A new inode for the new content, then every location linked to it again.
        sb.append("chmod 0644 \"$store.new\"\n");
        sb.append("mv -f \"$store.new\" \"$store\"\n");
        for (var path : declared) {
            sb.append(path.startsWith(GuestProvisioning.AGENTUSER_HOME + "/") ? "isx_copy " : "isx_link ")
                    .append(Container.shellQuote(path)).append('\n');
        }
        return sb.toString();
    }
}
