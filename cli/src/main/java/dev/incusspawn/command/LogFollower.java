package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandResult;

import java.io.IOException;
import java.util.List;

/** Follows a log on the terminal for the commands that show one: {@code isx proxy logs}, {@code isx vm console}. */
final class LogFollower {

    private LogFollower() {}

    /**
     * Runs {@code command} ({@code tail -f}, {@code journalctl -f}) on the terminal until it ends;
     * the command's result from how it ended ({@link #followed}). {@code failure} heads the error
     * when it cannot start at all.
     */
    static CommandResult follow(List<String> command, String failure) {
        // The follower inherits the terminal: only the CLI runs this, and nothing else prints meanwhile
        Process follower = null;
        try {
            var pb = new ProcessBuilder(command);
            pb.inheritIO();
            follower = pb.start();
            return CommandResult.valueOf(followed(follower.waitFor()));
        } catch (InterruptedException e) {
            // Nothing interrupts this thread; were it, the log was shown until then, and the
            // follower must not go on writing to the terminal under whatever runs next
            follower.destroy();
            Thread.currentThread().interrupt();
            return CommandResult.SUCCESS;
        } catch (IOException e) {
            BuildOutput.error(failure + ": " + e.getMessage());
            return CommandResult.valueOf(1);
        }
    }

    /**
     * The exit code of following a log, from the follower's: one ended by a signal (above 128)
     * was stopped by whoever read it, as {@code isx proxy logs | head} sends {@code tail}
     * SIGPIPE, so that is a success; one that exited by itself could not read the log, and has
     * said why.
     */
    static int followed(int exitCode) {
        return exitCode == 0 || exitCode > 128 ? 0 : 1;
    }
}
