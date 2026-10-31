package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqAnyGetter;
import io.hyperfoil.tools.jjq.mapper.JqAnySetter;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the keys of {@code config.yaml} that a mapped class does not declare, so a save writes
 * them back rather than deleting them, and {@link UnreadConfigKeys} can name them (#1876).
 *
 * <p>A superclass because the generated mapping counts every field the class itself declares as
 * a known key, {@code @JqIgnore} or not: a map declared there would make a key named
 * {@code extras} in the file neither bound nor kept. An inherited field is not counted.
 */
abstract class UndeclaredKeys {
    private final Map<String, Object> extras = new LinkedHashMap<>();

    @JqAnySetter
    public void setExtra(String key, Object value) { extras.put(key, value); }

    @JqAnyGetter
    public Map<String, Object> getExtras() { return extras; }
}
