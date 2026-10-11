package dev.incusspawn.command;

import dev.incusspawn.incus.IncusException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A listing that is not complete JSON is an error, never a shorter list: a script would take it for
 * the instances there are. jjq's plain parse reads these as values (Hyperfoil/jjq#127); the listing
 * uses its strict one.
 */
class ListingJsonStrictnessTest {

    @Test
    void aListingCutBetweenElementsFails() {
        assertThrows(IncusException.class, () -> InstanceListing.collectEntries("[{\"name\":\"a\"},"));
        assertThrows(IncusException.class, () -> InstanceListing.collectEntries("[{\"name\":\"a\"},{\"name\":\"b\""));
    }

    @Test
    void aTrailingCommaOrAnHtmlErrorPageFails() {
        assertThrows(IncusException.class, () -> InstanceListing.collectEntries("[{\"name\":\"a\"},]"));
        assertThrows(IncusException.class, () -> InstanceListing.collectEntries("<html>502 Bad Gateway</html>"));
    }

    @Test
    void contentAfterTheListingFailsWithoutQuotingIt() {
        var listing = "[" + "{\"name\":\"a\"},".repeat(500) + "{\"name\":\"b\"}] xyz";
        var e = assertThrows(IncusException.class, () -> InstanceListing.collectEntries(listing));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().length() < 300, e.getMessage());
    }

    @Test
    void anEmptyListingIsEmpty() {
        assertEquals(0, InstanceListing.collectEntries("[]").size());
    }
}
