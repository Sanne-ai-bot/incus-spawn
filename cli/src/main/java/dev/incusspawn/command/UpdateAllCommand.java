package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.HostRepoRefresh;
import dev.incusspawn.incus.Container;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.BuildAccounts;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.proxy.InstanceSecret;
import dev.incusspawn.proxy.ProxyService;
import dev.incusspawn.lifecycle.InstanceNetwork;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@CommandDefinition(
        name = "update-all",
        description = "Update system packages, npm globals, and git-fetch repos in all templates."
                + " Does not re-clone repos, reinstall tools, or re-run prime commands (use --prime for that)."
                + " For a full rebuild from the template definition, use 'isx build'.",
        generateHelp = true
)
public class UpdateAllCommand extends BaseCommand {

    /** What a template that did not take its pinned address names, and how to retry it. */
    static final InstanceNetwork.AddressUse UPDATE_ADDRESS = new InstanceNetwork.AddressUse("Template", "'isx update-all'");

    @Option(name = "prime", hasValue = false, description = "Re-run prime commands (e.g. 'mvn install -DskipTests') for repositories that define one")
    boolean prime;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (!InitCommand.requireInit()) return CommandResult.valueOf(1);
        return CommandResult.valueOf(updateAll(RuntimeServices.incus()));
    }

    /** Refreshes the host repos the templates use, updates every template and returns the command's exit code. */
    int updateAll(IncusClient incus) {
        var instances = incus.list();
        var templates = new ArrayList<String>();

        // Collect base images first, then project images (order matters for dependencies)
        for (var instance : instances) {
            var name = instance.get("name");
            var type = Metadata.getType(incus, name);
            if (Metadata.TYPE_BASE.equals(type)) {
                templates.add(0, name); // bases first
            } else if (Metadata.TYPE_PROJECT.equals(type)) {
                templates.add(name);
            }
        }

        if (templates.isEmpty()) {
            BuildOutput.error("No templates found. Run 'isx build' first.");
            return 1;
        }

        var defs = ImageDef.loadAll();
        var resolved = new LinkedHashMap<String, ImageDef>();
        for (var name : templates) {
            var profile = incus.configGet(name, Metadata.PROFILE);
            var templateName = (profile != null && !profile.isEmpty()) ? profile : name;
            resolved.put(name, defs.get(templateName));
        }

        refreshHostRepos(resolved, defs);

        return updateTemplates(incus, resolved);
    }

    /** Updates each template in {@code resolved} and returns the command's exit code. */
    int updateTemplates(IncusClient incus, Map<String, ImageDef> resolved) {
        BuildOutput.section("Updating " + resolved.size() + " template(s).");

        boolean primesSkipped = false;
        boolean leftRunning = false;
        var failedSteps = new LinkedHashSet<String>();
        for (var entry : resolved.entrySet()) {
            BuildOutput.header("Updating " + entry.getKey());
            var result = updateImage(incus, entry.getKey(), entry.getValue());
            primesSkipped |= result.primesSkipped();
            leftRunning |= result.leftRunning();
            failedSteps.addAll(result.failedSteps());
        }

        if (!failedSteps.isEmpty()) {
            BuildOutput.warn("Some " + String.join(", ", failedSteps)
                    + " failed; re-run 'isx update-all" + (prime ? " --prime" : "") + "' to retry them.");
        }
        // A re-run cannot retry a stop: each template left running was warned about on its own
        if (!failedSteps.isEmpty() || leftRunning) return 1;
        BuildOutput.success("All templates updated.");
        if (primesSkipped) {
            BuildOutput.note("Use --prime to re-run prime commands.");
        }
        return 0;
    }

    private void refreshHostRepos(Map<String, ImageDef> resolved, Map<String, ImageDef> defs) {
        var config = SpawnConfig.load();
        if (config.getHostPaths().isEmpty() && config.getRepoPaths().isEmpty()) return;

        var repos = new ArrayList<ImageDef.RepoEntry>();
        for (var imageDef : resolved.values()) {
            if (imageDef != null) {
                repos.addAll(HostRepoRefresh.collectAllRepos(imageDef, defs));
            }
        }
        if (repos.isEmpty()) return;

        HostRepoRefresh.refresh(repos, HostRepoRefresh.hostDepths(defs), config);
    }

    private record PrimeResult(boolean skipped, boolean failed) {
        static final PrimeResult NONE = new PrimeResult(false, false);
    }

    /**
     * {@code failedSteps} names each failed step in plural, for one summary line; {@code leftRunning}
     * is a template that could not be stopped, or was refused for not being stopped.
     */
    private record UpdateResult(boolean primesSkipped, Set<String> failedSteps, boolean leftRunning) {}

    private UpdateResult updateImage(IncusClient incus, String name, ImageDef imageDef) {
        var read = incus.readInstance(name);
        var machineType = IncusClient.machineType(read.metadata());
        // Before the try: a template refused here was not started, so is not this update's to stop
        try {
            InstanceLifecycle.requireStoppedTemplate(name, read);
        } catch (RuntimeException e) {
            // As one left running: a re-run cannot retry it, so the summary does not offer one
            BuildOutput.stepFail(e.getMessage());
            return new UpdateResult(false, Set.of(), true);
        }
        // Stopped however the update ends, even when its start failed: a template left running
        // fails the next update-all's start
        var failedSteps = new LinkedHashSet<String>();
        boolean primesSkipped = false;
        boolean leftRunning;
        boolean answered = false;
        try {
            // Pinned, filtered and granted its accounts as a build is, since it runs the same prime commands (#1743)
            var started = InstanceLifecycle.startTemplate(incus, name, read, BuildOutput.bound());
            incus.waitForReady(name, machineType, InstanceSecret.GUEST_SCRIPT, started.guestEnv());
            answered = true;

            // Every step below needs the network: without one, each would only fail the same way
            if (!holdsItsAddress(incus, name, started.address())) {
                failedSteps.add("address checks");
            } else if (!GuestUpdate.network(incus, name, machineType)) {
                failedSteps.add("network waits");
            } else {
                if (!GuestUpdate.system(incus, name)) failedSteps.add("system updates");
                // Update globally installed npm packages (coding tools, etc.)
                if (!NpmUpdate.run(incus, name)) failedSteps.add("npm updates");
                // Git fetch in all repos (for project images)
                if (!GuestUpdate.gitRepos(incus, name)) failedSteps.add("git fetches");
                var primeResult = handlePrimeCommands(incus, name, imageDef);
                if (primeResult.failed) failedSteps.add("prime commands");
                primesSkipped = primeResult.skipped;
            }
            GuestUpdate.emptyTmp(incus, name);
        } finally {
            // Before the stop, and whether or not the grant reached Incus: its write may have
            failedSteps.addAll(revokeGrant(incus, name, answered));
            leftRunning = !stop(incus, name);
            // Only once stopped, and even after a failed claim: its write may have reached Incus
            if (!leftRunning) InstanceNetwork.releaseBuildAddressOrWarn(incus, name, BuildOutput.bound());
        }
        return new UpdateResult(primesSkipped, failedSteps, leftRunning);
    }

    /**
     * Revoke the proofs {@link InstanceLifecycle#startTemplate} granted, and clear what the guest
     * cached of them from the rootfs its branches copy, as a build does before it publishes
     * ({@link BuildAccounts#clearGuestProofs}): only once the guest answered, since it receives
     * them in the readiness exec. Never throws, for the same reason as {@link #stop}.
     *
     * @return the failed steps, each warned about here
     */
    private static Set<String> revokeGrant(IncusClient incus, String name, boolean answered) {
        var failed = new LinkedHashSet<String>();
        try {
            BuildAccounts.revoke(incus, name);
            ProxyService.signalAccountRefresh();
        } catch (RuntimeException e) {
            BuildOutput.warn("Could not revoke " + name + "'s update credentials: " + e.getMessage(),
                    "They stay valid while it runs; its next update or build replaces them.");
            failed.add("credential revocations");
        }
        if (!answered) return failed;
        try {
            BuildAccounts.clearGuestProofs(new Container(incus, name));
        } catch (RuntimeException e) {
            BuildOutput.warn("Could not clear " + name + "'s update credentials from the guest: " + e.getMessage(),
                    "Branches start with fresh proofs of their own; rebuild the template to remove the stale cache.");
            failed.add("proof cache cleanups");
        }
        return failed;
    }

    /**
     * Whether the template's guest took the address it was pinned to; reported as a failed step
     * when it did not, since filtering drops all its traffic, so the next template still updates.
     */
    private static boolean holdsItsAddress(IncusClient incus, String name, String address) {
        try {
            InstanceNetwork.requireBuildAddress(incus, name, address, UPDATE_ADDRESS);
            return true;
        } catch (IncusException e) {
            BuildOutput.stepFail(e.getMessage());
            return false;
        }
    }

    /**
     * Stop the template, forcing it when its guest ignores the shutdown, as one whose agent never
     * answered may. One that died on its own is already stopped, which Incus refuses to stop again.
     * Never throws: from a {@code finally}, that would replace why the update failed.
     *
     * @return whether the template is stopped
     */
    private static boolean stop(IncusClient incus, String name) {
        try {
            incus.stop(name);
            return true;
        } catch (RuntimeException e) {
            if (isStoppedOrGone(incus, name)) return true;
            try {
                incus.forceStop(name);
                BuildOutput.warn("Force-stopped " + name + ": it did not shut down when asked.");
                return true;
            } catch (RuntimeException forced) {
                BuildOutput.warn("Could not stop " + name + ": " + e.getMessage() + "; forcing it: " + forced.getMessage(),
                        "The next update-all cannot start it until it is stopped.");
                return false;
            }
        }
    }

    /** Whether Incus says the template is stopped or no longer there; a read that fails says neither. */
    private static boolean isStoppedOrGone(IncusClient incus, String name) {
        try {
            var instance = incus.instanceMetadataOrThrow(name);
            return instance == null || "Stopped".equalsIgnoreCase(instance.pathText("status", ""));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private PrimeResult handlePrimeCommands(IncusClient incus, String name, ImageDef imageDef) {
        if (imageDef == null) return PrimeResult.NONE;

        var repos = imageDef.getRepos();
        if (repos.isEmpty()) return PrimeResult.NONE;

        boolean skipped = false;
        boolean failed = false;
        for (var repo : repos) {
            if (!repo.hasPrime()) continue;
            if (prime) {
                BuildOutput.stepStart("Priming " + repo.getPath() + "...");
                var run = RepoCloner.runPrime(new Container(incus, name), repo,
                        () -> BuildOutput.stepProgress("binding new runtimes to the isx CA, priming again"));
                if (run.success()) {
                    BuildOutput.stepDone();
                } else {
                    BuildOutput.stepFail("Prime command failed for " + repo.getPath() + ": " + run.command()
                            + (run.retried() ? "; " + run.label() : "")
                            // update-all shows no prime output: the advice goes on the line itself.
                            + (run.stillUntrusted() ? ". " + RepoCloner.UNTRUSTED_AFTER_BIND : ""));
                    failed = true;
                }
            } else {
                BuildOutput.note("Skipping prime for " + repo.getPath() + ": " + repo.primeText());
                skipped = true;
            }
        }
        if (prime && repos.stream().anyMatch(ImageDef.RepoEntry::hasPrime)) bindDownloadedRuntimes(incus, name);
        return new PrimeResult(skipped, failed);
    }

    /** Exit status of a shell command that does not exist: no isx-trust in the template. */
    private static final int COMMAND_NOT_FOUND = 127;

    /**
     * A runtime a prime downloaded (a Gradle toolchain, a mise JDK) would keep its own roots in
     * every later branch: the path units do not settle before the stop. Bind it, as a build's
     * bindTrustStores does. A template built before isx-trust has no helper, and nothing to bind;
     * a bind that fails otherwise warns rather than failing primes that passed.
     */
    private static void bindDownloadedRuntimes(IncusClient incus, String name) {
        String why;
        try {
            why = GuestProvisioning.runTrustHelper(new Container(incus, name)).stream()
                    .filter(r -> !r.success() && r.exitCode() != COMMAND_NOT_FOUND)
                    .map(r -> BuildProgress.lastNonEmptyLine(BuildProgress.combinedOutput(r)))
                    .findFirst().orElse(null);
        } catch (RuntimeException e) {
            why = e.getMessage();
        }
        if (why != null) {
            BuildOutput.warn("Could not bind the runtimes the primes downloaded in " + name + ": " + why,
                    "A JDK they downloaded may not trust the isx CA in later branches; run isx-trust in one.");
        }
    }

}
