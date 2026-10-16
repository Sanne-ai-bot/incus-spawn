package dev.incusspawn.git;

import dev.incusspawn.config.SecretRedactor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A host Git command that failed, with isx's own explanation kept apart from what Git said, so a
 * user can tell which words are whose (#1343). The message is isx's and names only the command: Git's
 * output can hold a URL, so it is forwarded in {@link #details()}, scrubbed, never in the message.
 */
public class GitCommandException extends IOException {
    private static final Pattern HTTP_USERINFO = Pattern.compile("(?i)\\b(https?://)[^/?#@\\s'\"]+@");
    private final List<String> details;

    GitCommandException(String message, String isxNote, String gitOutput) {
        super(message);
        details = details(isxNote, gitOutput);
    }

    /** isx's explanation, then Git's own lines quoted under a header; empty when there is neither. */
    public List<String> details() {
        return details;
    }

    static List<String> details(String isxNote, String gitOutput) {
        var lines = new ArrayList<String>();
        if (isxNote != null) lines.add(isxNote);
        if (gitOutput != null && !gitOutput.isBlank()) {
            // Bounded and cleaned like a transfer's tail, whichever command it came from.
            var said = gitOutput.lines().map(GitProcess.Tail::clean).filter(l -> !l.isEmpty()).toList();
            lines.add("Git's output:");
            said.subList(Math.max(0, said.size() - GitProcess.TAIL_LINES), said.size()).forEach(l -> lines.add("  | " + l));
        }
        return lines;
    }

    /**
     * {@code text} without URL credentials: the redactor's {@code user:password@} and, beyond it, the
     * whole userinfo of an HTTP(S) URL, where a token can stand alone.
     */
    static String scrub(String text) {
        return HTTP_USERINFO.matcher(SecretRedactor.scrubText(text, Map.of()).text())
                .replaceAll("$1" + SecretRedactor.marker("url-credentials") + "@");
    }

    /**
     * isx's explanation for a failure that a prompt would have answered, or null when isx changed
     * nothing that explains it. {@code ssh}: what isx did.
     */
    static String promptNote(String gitOutput, PreparationGit.SshSetup ssh) {
        if (gitOutput == null) return null;
        if (gitOutput.contains("Permission denied (publickey")) {
            // Also what a server says when it accepts none of the keys: isx cannot tell which.
            return ssh.done() + ", so ssh could not ask for a key's passphrase. If your key has one, load it"
                    + " into ssh-agent; otherwise check that the server accepts the key, or choose another with "
                    + ssh.keySetting() + ".";
        }
        if (gitOutput.contains("Host key verification failed")) {
            return ssh.done() + ", so ssh could not ask about the host's key. If the host is new, check its"
                    + " fingerprint and add it to ~/.ssh/known_hosts; if its key changed, find out why before"
                    + " trusting it.";
        }
        if (gitOutput.contains("terminal prompts disabled") || gitOutput.contains("could not read Username")
                || gitOutput.contains("could not read Password")) {
            return "isx ran Git with GIT_TERMINAL_PROMPT=0 and no askpass program, so it could not ask for a"
                    + " username or password. Configure a Git credential helper.";
        }
        return null;
    }
}
