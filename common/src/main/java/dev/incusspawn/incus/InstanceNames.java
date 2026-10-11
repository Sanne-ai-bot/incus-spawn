package dev.incusspawn.incus;

import java.util.regex.Pattern;

/** What Incus accepts as an instance name. */
public final class InstanceNames {

    /** The longest instance name Incus accepts. */
    public static final int MAX_LENGTH = 63;

    /** Letters, digits and '-', neither first nor last, and not a number (Incus's {@code IsHostname}). */
    private static final Pattern VALID =
            Pattern.compile("(?![0-9]+$)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0," + (MAX_LENGTH - 2) + "}[a-zA-Z0-9])?");

    /** How {@link #isValid} reads to a user. */
    public static final String RULE = "letters, digits and '-', not starting or ending with '-', not only digits,"
            + " at most " + MAX_LENGTH + " characters";

    private InstanceNames() {}

    /** Whether Incus would accept {@code name} for an instance; false for null. */
    public static boolean isValid(String name) {
        return name != null && VALID.matcher(name).matches();
    }
}
