package dev.incusspawn.util;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.Function;

/**
 * The strong validator of an HTTP response: what a {@code Range} request may continue its body
 * against ({@code If-Range}), and what a {@code 206} must carry to be the rest of the same file.
 * Headers are read through {@code header}, which gives null for one that is absent.
 */
public final class HttpValidator {

    private HttpValidator() {}

    /**
     * A strong {@code ETag}, or else a {@code Last-Modified} at least a second older than the
     * response's {@code Date}, as RFC 9110 (8.8.2.2) lets it stand in: a file changed twice within
     * that second would keep it. Where there is an {@code ETag}, {@code Last-Modified} may not
     * stand in (13.1.5), so a weak one leaves none. Null when there is none.
     */
    public static String strong(Function<String, String> header) {
        var etag = header.apply("ETag");
        if (etag != null) return etag.startsWith("W/") ? null : etag;
        var lastModified = header.apply("Last-Modified");
        var date = header.apply("Date");
        if (lastModified == null || date == null) return null;
        try {
            var modified = ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            var sent = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return sent.minusSeconds(1).isBefore(modified) ? null : lastModified;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Whether a {@code 206} is the rest, from {@code offset}, of the file of {@code length} bytes a
     * response with {@code validator} started: to its end, as it is stored (no {@code Content-Encoding}),
     * and with that validator itself, since some servers ignore {@code If-Range} (#900).
     */
    public static boolean continues(String validator, long length, long offset, Function<String, String> header) {
        return ("bytes " + offset + "-" + (length - 1) + "/" + length).equals(header.apply("Content-Range"))
                && header.apply("Content-Encoding") == null
                && contentLength(header) == length - offset
                && validator != null && validator.equals(strong(header));
    }

    /** The {@code Content-Length}, or -1 when it is absent or not a number. */
    public static long contentLength(Function<String, String> header) {
        var value = header.apply("Content-Length");
        try {
            return value == null ? -1 : Long.parseLong(value.strip());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
