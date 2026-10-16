package dev.incusspawn.util;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * The strong validator of an HTTP response: what a {@code Range} request may continue its body
 * against ({@code If-Range}), and what a {@code 206} must carry to be the rest of the same file.
 */
public final class HttpValidator {

    private HttpValidator() {}

    /**
     * A strong {@code ETag}, or else a {@code Last-Modified} at least a second older than the
     * response's {@code Date}, as RFC 9110 (8.8.2.2) lets it stand in: a file changed twice within
     * that second would keep it. Where there is an {@code ETag}, {@code Last-Modified} may not
     * stand in (13.1.5), so a weak one leaves none. Null when there is none.
     */
    public static String strong(String etag, String lastModified, String date) {
        if (etag != null) return etag.startsWith("W/") ? null : etag;
        if (lastModified == null || date == null) return null;
        try {
            var modified = ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            var sent = ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return sent.minusSeconds(1).isBefore(modified) ? null : lastModified;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
