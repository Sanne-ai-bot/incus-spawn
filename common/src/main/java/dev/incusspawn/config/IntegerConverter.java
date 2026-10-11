package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.ValueConverter;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.quarkus.runtime.annotations.RegisterForReflection;


/**
 * An {@code Integer} setting as Jackson bound it: a whole number in the int range, or a string
 * holding one ({@code "24"}); absent or empty is null. Anything else fails the file, with the field
 * and line the mapper adds. jjq 0.2.2 binds 0 for a word or a boolean and wraps an out-of-range
 * number (Hyperfoil/jjq#133), which fails open: an orphan grace of 0 reaps at once, and a wrapped
 * {@code max-instances} is no cap. A fraction is refused too, where Jackson truncated it.
 */
@RegisterForReflection
public class IntegerConverter implements ValueConverter<Integer> {

    @Override
    public Integer fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return null;
        Integer bound = null;
        if (value.isNumber()) {
            bound = Jjq.exactInt(value);
        } else if (value.isString()) {
            if (value.asText().isBlank()) return null; // as Jackson bound "" to a wrapper
            try {
                bound = Integer.parseInt(value.asText().strip());
            } catch (NumberFormatException notAnInteger) {
                // refused below
            }
        }
        if (bound != null) return bound;
        throw new JqMapperException("expects a whole number, but got " + shown(value));
    }

    private static String shown(JqValue value) {
        var text = value.isString() ? "'" + value.asText() + "'" : Jjq.text(value);
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }

    @Override
    public JqValue toJqValue(Integer value) {
        return value == null ? JqNull.NULL : JqNumber.of((long) value);
    }
}
