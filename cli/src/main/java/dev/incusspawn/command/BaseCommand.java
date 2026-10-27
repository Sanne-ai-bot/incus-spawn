package dev.incusspawn.command;

import dev.incusspawn.util.BuildOutput;
import org.aesh.command.Command;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;

import java.util.concurrent.Callable;
import java.util.function.Supplier;

public abstract class BaseCommand implements Command<CommandInvocation> {

    protected CommandInvocation commandInvocation;

    @Override
    public CommandResult execute(CommandInvocation invocation) throws InterruptedException {
        this.commandInvocation = invocation;
        try {
            return doExecute();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            BuildOutput.abandonStep();
            BuildOutput.error(failure(e));
            return CommandResult.valueOf(1);
        } finally {
            // A step an exception cut short must not stay animated, nor keep the streams guarded
            // for a TUI that resumes in this process after the command.
            BuildOutput.abandonStep();
        }
    }

    protected CommandResult doExecute() throws Exception {
        return CommandResult.SUCCESS;
    }

    /** What a failed command says: the exception's message, or the exception's class when it has none. */
    public static String failure(Exception e) {
        var message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getName() : message;
    }

    /** Run {@code work} with {@code System.out} pointed at stderr: see {@link StdoutOnStderr}. */
    protected static <T> T withStdoutOnStderr(Callable<T> work) throws Exception {
        return StdoutOnStderr.during(work);
    }

    /**
     * A yes/no question asked through {@code prompts}, which shows it at the step indent and
     * reads the answer: empty takes {@code defaultValue}, EOF {@code eofValue}, and anything else
     * is asked again (#1703). Callers decide how to handle an unavailable console.
     */
    protected static boolean askYesNo(Prompts prompts, String question, boolean defaultValue, boolean eofValue) {
        while (true) {
            var answer = prompts.ask(question + (defaultValue ? " (Y/n): " : " (y/N): "));
            if (answer == null) return eofValue;

            var parsed = parseConfirmation(answer, defaultValue);
            if (parsed != null) return parsed;
            prompts.say("Please answer y or n.");
        }
    }

    /** {@link #askYesNo(Prompts, String, boolean, boolean)}, EOF declining. */
    protected static boolean askYesNo(Prompts prompts, String question, boolean defaultValue) {
        return askYesNo(prompts, question, defaultValue, false);
    }

    /**
     * Ask before a disruptive but recoverable action (a restart, a resize). With no terminal it
     * proceeds; anything that deletes data uses {@link #confirmDestructive} instead.
     */
    protected static boolean confirm(String prompt, boolean skipConfirmation) {
        return confirm(prompt, skipConfirmation, Prompts::terminal);
    }

    /**
     * {@link #confirm} asking through the {@code terminal} it gets once no skip flag answered,
     * null when there is none ({@link Prompts#terminal}).
     */
    static boolean confirm(String prompt, boolean skipConfirmation, Supplier<Prompts> terminal) {
        if (skipConfirmation) return true;
        var prompts = terminal.get();
        if (prompts == null) return true;
        if (!askYesNo(prompts, prompt, false)) {
            BuildOutput.step("Aborted.");
            return false;
        }
        return true;
    }

    /** {@code items}, each on its own line, in a group under {@code title}; nothing when there are none. */
    protected static void groupOfSteps(String title, java.util.List<String> items) {
        if (items.isEmpty()) return;
        try (var group = BuildOutput.group(title, null)) {
            items.forEach(BuildOutput::step);
        }
    }

    /**
     * {@link #confirm} for an action that deletes data. With no terminal to ask on, {@code confirm}
     * proceeds without reading stdin, so {@code echo n | isx ...} deletes anyway; this refuses
     * instead, by throwing {@link NoTerminalException} (which {@link #execute} reports as an error
     * and exit status 1). Declining at the prompt returns {@code false}, which is not an error.
     *
     * @param skipFlag the option that skips the question, named in the refusal
     */
    protected static boolean confirmDestructive(String prompt, boolean skipConfirmation, String skipFlag) {
        return confirmDestructive(prompt, skipConfirmation, skipFlag, Prompts::terminal);
    }

    /** {@link #confirmDestructive} asking through the {@code terminal} it gets once no skip flag answered. */
    static boolean confirmDestructive(String prompt, boolean skipConfirmation, String skipFlag,
                                      Supplier<Prompts> terminal) {
        if (skipConfirmation) return true;
        var prompts = terminal.get();
        if (prompts == null) throw new NoTerminalException(skipFlag);
        if (!askYesNo(prompts, prompt, false)) {
            BuildOutput.step("Aborted.");
            return false;
        }
        return true;
    }

    static final class NoTerminalException extends RuntimeException {
        NoTerminalException(String skipFlag) {
            super("this deletes data and there is no terminal to confirm it on. Re-run with "
                    + skipFlag + " to proceed without asking.");
        }
    }

    static Boolean parseConfirmation(String answer, boolean defaultValue) {
        if (answer == null) return null;
        var normalized = answer.strip();
        if (normalized.isEmpty()) return defaultValue;
        if (normalized.equalsIgnoreCase("y")) return true;
        if (normalized.equalsIgnoreCase("n")) return false;
        return null;
    }
}
