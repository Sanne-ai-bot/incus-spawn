package dev.incusspawn.incus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "tpl-java", "Tpl-9-ok", "1a", "a1",
            "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij123"})
    void namesIncusAccepts(String name) {
        assertTrue(InstanceNames.isValid(name), name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "-a", "a-", "tpl-", "7", "123", "tpl_java", "tpl.java", "tpl java", "tpl-\u001b[2J",
            "abcdefghijabcdefghijabcdefghijabcdefghijabcdefghijabcdefghij1234"})
    void namesIncusRefuses(String name) {
        assertFalse(InstanceNames.isValid(name), name);
    }

    @Test
    void nullIsNoName() {
        assertFalse(InstanceNames.isValid(null));
    }
}
