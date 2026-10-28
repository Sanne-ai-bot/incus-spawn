package dev.incusspawn.incus;

import java.time.Duration;

/**
 * Where a build keeps every command a definition declared and what it printed (#1742): a tool's
 * {@code run}, {@code run_as_user} and {@code verify} commands and a repository's {@code prime}. Only those, never
 * isx's own commands, which can carry an instance's proofs. Called from the exec's thread, and
 * repositories prime in parallel, so an implementation is thread-safe.
 */
public interface CommandLog {

    /** A container whose declared commands are kept nowhere. */
    CommandLog NONE = (step, command, result, took) -> {};

    /**
     * {@code command} ran for {@code step} (such as {@code tool 'maven' run}) and ended with
     * {@code result} after {@code took}.
     */
    void record(String step, String command, IncusClient.ExecResult result, Duration took);
}
