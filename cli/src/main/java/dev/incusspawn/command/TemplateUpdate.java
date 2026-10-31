package dev.incusspawn.command;

import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.InstanceNames;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.lifecycle.TemplateLock;
import dev.incusspawn.util.BuildOutput;

import java.util.Map;

/**
 * Updating a template as a build replaces one (#1760): {@code isx update-all} and
 * {@code isx project update} run in a copy of the template, made under the build's temporary name
 * and untrusted from its copy request on, and put it in place of the template only once every
 * step succeeded. A step that fails, or anything thrown, deletes the copy and leaves the template
 * as it was, so a branch never copies a half-updated template, and one made meanwhile copies the
 * consistent one the update started from.
 */
final class TemplateUpdate {

    private TemplateUpdate() {}

    /** The update's steps, run in {@code copy}, a stopped copy of the template. */
    interface Steps {
        /** Whether every step succeeded and {@code copy} is stopped again: only then is it swapped in. */
        boolean run(String copy);
    }

    /** What an update's copy is named: the build's temporary name, which listings already hide. */
    static String copyName(String template) {
        return template + BuildCommand.REBUILDING_SUFFIX;
    }

    /**
     * Copy {@code template}, which {@code read} describes and must be a stopped template, run
     * {@code steps} in the copy and swap it in when they succeed.
     *
     * @return whether the template was replaced by its update; when not, each failure was reported
     * @throws RuntimeException whatever a step threw, once the copy is deleted
     */
    static boolean run(IncusClient incus, String template, IncusClient.InstanceRead read, Steps steps) {
        InstanceLifecycle.requireStoppedTemplate(template, read);
        var copy = copyName(template);
        if (!InstanceNames.isValid(copy)) {
            throw new IllegalStateException("'" + template + "' is too long to update: it is updated as '" + copy
                    + "', and Incus allows " + InstanceNames.MAX_LENGTH + " characters. Rebuild it under a shorter name.");
        }
        if (BuildCommand.reportStrandedStorage(incus, template, copy)) return false;
        var config = read.metadata().path("config");
        var lineage = config.pathText(Metadata.UNTRUSTED_LINEAGE, "");
        // Which instance the update started from: a rebuild swapped in meanwhile is not replaced
        var copiedFrom = read.metadata().pathText("created_at", "");

        // What an update that died before its swap left behind
        incus.deleteIfExists(copy);
        boolean updated;
        try {
            // Held until the copy is made, so a rebuild cannot swap the template away meanwhile (#1212)
            try (var held = TemplateLock.reading(template, BuildOutput.bound())) {
                // Untrusted from the copy on, as a build is, until it is in place; one that already
                // was keeps its reason. Its size is measured again once it is updated.
                incus.copy(template, copy, incus.planCopy(read.metadata()), Map.of(
                        Metadata.UNTRUSTED_LINEAGE, lineage.isEmpty() ? Metadata.UNTRUSTED_BUILDING : lineage,
                        Metadata.DISK_REFERENCED, ""));
            }
            updated = steps.run(copy);
        } catch (RuntimeException e) {
            try {
                incus.deleteIfExists(copy);
            } catch (RuntimeException deleteFailed) {
                e.addSuppressed(deleteFailed);
            }
            throw e;
        }
        if (!updated) {
            discard(incus, copy, template + " is unchanged.");
            return false;
        }
        // Measured before the swap deletes the template's subvolume, as a build's is
        var referenced = BuildCommand.probeReferencedSize(incus, copy);
        boolean swapped = TemplateLock.replaceIf(incus, copy, template, BuildOutput.bound(),
                () -> copiedFrom.equals(createdAt(incus, template)),
                () -> {
                    if (lineage.isEmpty()) BuildCommand.trustBuilt(incus, template);
                });
        if (!swapped) {
            BuildOutput.stepFail(template + " was rebuilt, updated or removed by another isx while this update ran.");
            discard(incus, copy, "what is in its place is kept.");
            return false;
        }
        BuildCommand.stampReferencedSize(incus, template, referenced);
        return true;
    }

    /** When the instance now named {@code template} was created; null when there is none. */
    private static String createdAt(IncusClient incus, String template) {
        var instance = incus.instanceMetadataOrThrow(template);
        return instance == null ? null : instance.pathText("created_at", "");
    }

    /** Delete the update's copy, saying what was kept; one left behind is deleted by the next update. */
    private static void discard(IncusClient incus, String copy, String kept) {
        BuildOutput.stepStart("Discarding the update; " + kept);
        try {
            incus.delete(copy, true);
            BuildOutput.stepDone();
        } catch (RuntimeException e) {
            BuildOutput.stepFail("Could not delete " + copy + ": " + e.getMessage());
            BuildOutput.warn(copy + " is left behind.", "The next update deletes it, or run 'incus delete --force " + copy + "'.");
        }
    }
}
