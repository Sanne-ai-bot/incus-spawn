package dev.incusspawn.command;

import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.tool.NpmGlobalInstall;
import dev.incusspawn.util.BuildOutput;

/** The {@code npm update -g} step shared by {@code isx update-all} and {@code isx project update}. */
final class NpmUpdate {

    private NpmUpdate() {
    }

    /**
     * Updates the template's global npm packages, if it has npm. Returns false when the update
     * failed or left a CLI without its platform binary: npm exits 0 when it drops an optional
     * dependency, so that is checked (and repaired) separately (#808). Both wait for a release
     * still reaching the registry (#1757).
     */
    static boolean run(IncusClient incus, String name) {
        if (!incus.shellExec(name, "which", "npm").success()) return true;
        BuildOutput.stepStart("Updating npm packages...");
        var container = new Container(incus, name);
        var update = NpmGlobalInstall.update(container);
        if (!update.success()) return GuestUpdate.finish(update, "npm update -g");
        var problems = NpmGlobalInstall.repairAfterUpdate(container);
        if (!problems.isEmpty()) {
            BuildOutput.stepBreak();
            problems.forEach(System.err::println);
            return false;
        }
        BuildOutput.stepDone();
        return true;
    }
}
