package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.config.SpawnConfig;
import dev.incusspawn.git.HostRepoRefresh;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

import static dev.incusspawn.incus.Container.shellQuote;

@CommandDefinition(
        name = "update-all",
        description = "Update system packages, npm globals, and git-fetch repos in all templates."
                + " Does not re-clone repos, reinstall tools, or re-run prime commands (use --prime for that)."
                + " For a full rebuild from the template definition, use 'isx build'.",
        generateHelp = true
)
public class UpdateAllCommand extends BaseCommand {

    @Option(name = "prime", hasValue = false, description = "Re-run prime commands (e.g. 'mvn install -DskipTests') for repositories that define one")
    private boolean prime;

    @Override
    protected CommandResult doExecute() throws Exception {
        if (!InitCommand.requireInit()) return CommandResult.valueOf(1);
        var incus = RuntimeServices.incus();
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
            System.out.println("No templates found. Run 'isx build' first.");
            return CommandResult.valueOf(1);
        }

        var defs = ImageDef.loadAll();
        var resolved = new LinkedHashMap<String, ImageDef>();
        for (var name : templates) {
            var profile = incus.configGet(name, Metadata.PROFILE);
            var templateName = (profile != null && !profile.isEmpty()) ? profile : name;
            resolved.put(name, defs.get(templateName));
        }

        refreshHostRepos(resolved, defs);

        return CommandResult.valueOf(updateTemplates(incus, resolved));
    }

    /** Updates each template in {@code resolved} (in order) and returns the command's exit code. */
    int updateTemplates(IncusClient incus, Map<String, ImageDef> resolved) {
        BuildOutput.section("Updating " + resolved.size() + " template(s).");

        boolean primesSkipped = false;
        boolean primesFailed = false;
        boolean npmFailed = false;
        boolean dnfFailed = false;
        boolean gitFailed = false;
        for (var entry : resolved.entrySet()) {
            BuildOutput.header("Updating " + entry.getKey());
            var result = updateImage(incus, entry.getKey(), entry.getValue());
            primesSkipped |= result.primesSkipped();
            primesFailed |= result.primesFailed();
            npmFailed |= result.npmFailed();
            dnfFailed |= result.dnfFailed();
            gitFailed |= result.gitFailed();
        }

        if (dnfFailed) {
            BuildOutput.warn("Some system updates failed; re-run 'isx update-all' to retry them.");
        }
        if (npmFailed) {
            BuildOutput.warn("Some npm updates failed; re-run 'isx update-all' to retry them.");
        }
        if (gitFailed) {
            BuildOutput.warn("Some git fetches failed; re-run 'isx update-all' to retry them.");
        }
        if (primesFailed) {
            BuildOutput.warn("Some prime commands failed.");
        }
        if (dnfFailed || npmFailed || gitFailed || primesFailed) {
            return 1;
        }
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

        HostRepoRefresh.refresh(repos, config, false, System.out::println);
    }

    private record PrimeResult(boolean skipped, boolean failed) {
        static final PrimeResult NONE = new PrimeResult(false, false);
    }

    private record UpdateResult(boolean primesSkipped, boolean primesFailed, boolean npmFailed,
                                boolean dnfFailed, boolean gitFailed) {}

    /**
     * Fetches every git repository directly under agentuser's home. It is the script itself, not
     * an argument to {@code sh -c}: {@link IncusClient#execInContainer} joins its arguments with
     * spaces into {@code su - -c}, which would leave an unquoted loop that never parses (#1179).
     * Exits non-zero, naming each repository, when any fetch failed.
     */
    static final String GIT_FETCH_SCRIPT = "failed=0; for d in ~/*/; do if [ -d \"$d/.git\" ]; then"
            + " echo \"  Fetching $d\"; git -C \"$d\" fetch --all"
            + " || { echo \"git fetch failed in $d\" >&2; failed=1; }; fi; done; exit $failed";

    private UpdateResult updateImage(IncusClient incus, String name, ImageDef imageDef) {
        var machineType = incus.machineType(name);
        incus.start(name);
        incus.waitForReady(name, machineType);

        // System updates
        BuildOutput.stepStart("Running system updates...");
        var dnf = incus.shellExec(name, "dnf", "update", "-y");
        boolean dnfFailed = !dnf.success();
        if (dnfFailed) {
            BuildOutput.stepFail("dnf update -y failed (exit code " + dnf.exitCode() + "): "
                    + dnf.stderr().strip());
        } else {
            BuildOutput.stepDone();
        }

        // Update globally installed npm packages (coding tools, etc.)
        boolean npmFailed = !NpmUpdate.run(incus, name);

        // Git fetch in all repos (for project images)
        BuildOutput.stepStart("Updating git repositories...");
        var git = incus.execInContainer(name, "agentuser", GIT_FETCH_SCRIPT);
        boolean gitFailed = !git.success();
        if (gitFailed) {
            BuildOutput.stepFail("git fetch failed (exit code " + git.exitCode() + "): "
                    + git.stderr().strip());
        } else {
            BuildOutput.stepDone();
        }

        var primeResult = handlePrimeCommands(incus, name, imageDef);

        incus.stop(name);
        return new UpdateResult(primeResult.skipped, primeResult.failed, npmFailed, dnfFailed, gitFailed);
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
                var expanded = BuildCommand.expandHome(repo.getPath());
                BuildOutput.stepStart("Priming " + repo.getPath() + "...");
                var result = incus.execInContainer(name, "agentuser",
                        "cd " + shellQuote(expanded) + " && " + repo.getPrime());
                if (result.success()) {
                    BuildOutput.stepDone();
                } else {
                    BuildOutput.stepFail("Prime command failed for " + repo.getPath() + ": " + repo.getPrime());
                    failed = true;
                }
            } else {
                BuildOutput.note("Skipping prime for " + repo.getPath() + ": " + repo.getPrime());
                skipped = true;
            }
        }
        return new PrimeResult(skipped, failed);
    }

}
