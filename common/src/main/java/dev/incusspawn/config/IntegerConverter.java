package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.ValueConverter;
import io.hyperfoil.tools.jjq.value.JqNumber;
import io.hyperfoil.tools.jjq.value.JqValue;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.math.BigDecimal;

/**
 * An {@code Integer} setting as Jackson bound it: a whole number in the int range, or a string
 * holding one ({@code "24"}); absent or empty is null. Anything else fails the file, with the field
 * and line the mapper adds. jjq 0.2.2 binds 0 for a word or a boolean and wraps an out-of-range
 * number (Hyperfoil/jjq#133), which fails open: an orphan grace of 0 reaps at once, and a wrapped
 * {@code max-instances} is no cap. A fraction is refused too, where Jackson truncated it.
 */
@RegisterForReflection
public class IntegerConverter implements ValueConverter<Integer> {

    private static final BigDecimal INT_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INT_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);

    @Override
    public Integer fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return null;
        BigDecimal number = null;
        if (value.isNumber()) {
            number = value.decimalValue();
        } else if (value.isString()) {
            if (value.asText().isBlank()) return null; // as Jackson bound "" to a wrapper
            try {
                number = new BigDecimal(value.asText().strip());
            } catch (NumberFormatException notANumber) {
                // refused below
            }
        }
        // Bounds before intValueExact, which would otherwise look at every digit of a huge literal
        if (number != null && number.compareTo(INT_MIN) >= 0 && number.compareTo(INT_MAX) <= 0) {
            try {
                return number.intValueExact();
            } catch (ArithmeticException fractional) {
                // refused below
            }
        }
        throw new JqMapperException("expects a whole number, but got " + shown(value));
    }

    private static String shown(JqValue value) {
        var text = value.isString() ? "'" + value.asText() + "'" : Jjq.text(value);
        return text.length() > 40 ? text.substring(0, 40) + "..." : text;
    }

    @Override
    public JqValue toJqValue(Integer value) {
        return value == null ? io.hyperfoil.tools.jjq.value.JqNull.NULL : JqNumber.of((long) value);
    }
}
