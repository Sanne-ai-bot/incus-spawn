package dev.incusspawn.command;

import java.util.concurrent.Callable;

/**
 * Points {@code System.out} at stderr while a command in a machine format gets its answer
 * (#1036): stdout holds only its results, so whatever prints progress or notes on the way there
 * goes to stderr. A gate of {@code RawOutputGateTest}, as {@code StdioGuard} is for {@code isx mcp}.
 */
final class StdoutOnStderr {

    private StdoutOnStderr() {}

    static <T> T during(Callable<T> work) throws Exception {
        var out = System.out;
        System.setOut(System.err);
        try {
            return work.call();
        } finally {
            System.setOut(out);
        }
    }
}
