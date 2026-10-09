package dev.incusspawn.incus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Config and device changes to one instance, collected so {@link IncusClient#update} can send
 * them as a single write.
 *
 * <p>Every instance write costs Incus far more than the request itself: it takes the
 * instance's lock, applies the change and rewrites the instance's backup file each time
 * (~11-13 ms on btrfs on Linux; 6-10 ms in the macOS appliance, where the backup file is the
 * last millisecond of that and the vsock tunnel adds about a tenth of one). Steps that each
 * did their own read-modify-write add that up; collecting their changes here pays it once.
 */
public final class InstanceUpdate {

    private final Map<String, String> config = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> deviceProperties = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> replacedDevices = new LinkedHashMap<>();
    private final Set<String> removedDevices = new LinkedHashSet<>();

    /** Set a config key; {@code null} removes it. */
    public InstanceUpdate config(String key, String value) {
        config.put(key, value);
        return this;
    }

    public InstanceUpdate config(Map<String, String> entries) {
        config.putAll(entries);
        return this;
    }

    public InstanceUpdate unset(String key) {
        return config(key, null);
    }

    /**
     * Set one property of a device, keeping the rest of its config: a profile-inherited device
     * becomes an instance device with the property overridden.
     */
    public InstanceUpdate device(String deviceName, String key, String value) {
        deviceProperties.computeIfAbsent(deviceName, d -> new LinkedHashMap<>()).put(key, value);
        return this;
    }

    /**
     * Set a device to exactly this config, discarding what it had. Replacing a profile device
     * with {@code type: none} is how Incus masks it on one instance: removing an instance device
     * of the same name only drops the override, and the profile's device applies again.
     */
    public InstanceUpdate replaceDevice(String deviceName, Map<String, String> deviceConfig) {
        replacedDevices.put(deviceName, new LinkedHashMap<>(deviceConfig));
        return this;
    }

    /** Drop the device, if the instance itself declares it. Profile-inherited devices stay. */
    public InstanceUpdate removeDevice(String deviceName) {
        removedDevices.add(deviceName);
        return this;
    }

    /** Remove a previously requested device property, e.g. to retry the write without it. */
    public InstanceUpdate withoutDeviceProperty(String deviceName, String key) {
        var props = deviceProperties.get(deviceName);
        if (props != null) {
            props.remove(key);
            if (props.isEmpty()) deviceProperties.remove(deviceName);
        }
        return this;
    }

    public boolean isEmpty() {
        return config.isEmpty() && deviceProperties.isEmpty() && replacedDevices.isEmpty()
                && removedDevices.isEmpty();
    }

    /** Config changes, a {@code null} value meaning "remove". */
    Map<String, String> config() {
        return Collections.unmodifiableMap(config);
    }

    Map<String, Map<String, String>> deviceProperties() {
        return Collections.unmodifiableMap(deviceProperties);
    }

    Map<String, Map<String, String>> replacedDevices() {
        return Collections.unmodifiableMap(replacedDevices);
    }

    Set<String> removedDevices() {
        return Collections.unmodifiableSet(removedDevices);
    }
}
