package dev.incusspawn.lifecycle;

import dev.incusspawn.util.Output;

import java.util.ArrayList;
import java.util.List;

/**
 * Why something a branch asked for cannot be had on this host: the cause, and what to do about
 * it, one line each. A caller warns of it once, with what it does instead (#1690).
 */
public record Unavailable(String cause, List<String> advice) {

    public Unavailable(String cause, String... advice) {
        this(cause, List.of(advice));
    }

    /** One warning to {@code out}: the cause, then its advice and what is done instead under it. */
    public void warn(Output out, String instead) {
        var details = new ArrayList<>(advice);
        details.add(instead);
        out.warn(cause, details.toArray(String[]::new));
    }
}
