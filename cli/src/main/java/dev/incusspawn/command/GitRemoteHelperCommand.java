package dev.incusspawn.command;

import dev.incusspawn.config.ImageDef;
import dev.incusspawn.git.GitRemoteUtils;
import dev.incusspawn.util.OutputFormat;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Argument;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@CommandDefinition(
        name = "git-remote-helper",
        description = "Git remote helper for isx:// URLs (invoked by git, not directly)",
        generateHelp = true
)
public class GitRemoteHelperCommand extends BaseCommand {

    private static final Set<String> ALLOWED_SERVICES = Set.of("git-upload-pack", "git-receive-pack");

    @Argument(index = "0", description = "Instance name", required = true)
    String instance;

    @Argument(index = "1", description = "Git service (git-upload-pack or git-receive-pack)", required = true)
    String service;

    @Argument(index = "2", description = "Repository path inside the container", required = true)
    String path;


    @Override
    public CommandResult execute(CommandInvocation invocation) throws InterruptedException {
        var incus = dev.incusspawn.RuntimeServices.incus();

        if (!ALLOWED_SERVICES.contains(service)) {
            System.err.println("Error: unknown git service: " + OutputFormat.oneLine(service));
            return CommandResult.valueOf(1);
        }

        if (!checkInstanceRunning(incus)) {
            return CommandResult.valueOf(1);
        }

        var resolvedPath = GitRemoteUtils.expandContainerTilde(path);

        var stderrCapture = new StringBuilder();
        var stderrOut = new OutputStream() {
            public void write(int b) {
                var c = (char) b;
                stderrCapture.append(c);
                System.err.print(c);
            }
            public void write(byte[] b, int off, int len) {
                var s = new String(b, off, len, StandardCharsets.UTF_8);
                stderrCapture.append(s);
                System.err.print(s);
            }
        };

        int exitCode = incus.execBidirectional(instance, 1000, 1000, "/home/agentuser",
                new String[]{service, resolvedPath}, System.in, System.out, stderrOut);
        if (exitCode != 0 && stderrCapture.toString().contains("not a git repository")) {
            printRepoHints(incus);
        }
        return CommandResult.valueOf(exitCode);
    }

    private boolean checkInstanceRunning(dev.incusspawn.incus.IncusClient incus) {
        try {
            if (!incus.exists(instance)) {
                System.err.println("Error: instance '" + OutputFormat.oneLine(instance) + "' does not exist.");
                return false;
            }
            var status = incus.getInstanceStatus(instance);
            if (!"Running".equalsIgnoreCase(status)) {
                System.err.println("Error: instance '" + OutputFormat.oneLine(instance) + "' is not running (status: " + status + ").");
                System.err.println("Start it first: isx shell " + OutputFormat.oneLine(instance));
                return false;
            }
            return true;
        } catch (Exception e) {
            System.err.println("Error: could not check instance status: " + OutputFormat.oneLine(String.valueOf(e.getMessage())));
            return false;
        }
    }

    private void printRepoHints(dev.incusspawn.incus.IncusClient incus) {
        var repos = GitRemoteUtils.collectReposForInstance(instance, incus);
        if (repos.isEmpty()) return;

        System.err.println();
        repoHints(instance, path, repos).forEach(System.err::println);
    }

    /**
     * The hint for a path that is not a repository, as text: a template, project-local included,
     * decides its repositories' paths and URLs, and a clone's remote URL the requested path (#1624).
     */
    static List<String> repoHints(String instance, String path, List<ImageDef.RepoEntry> repos) {
        var lines = new ArrayList<String>();
        lines.add("The path '" + OutputFormat.oneLine(path) + "' is not a git repository in instance '"
                + OutputFormat.oneLine(instance) + "'.");
        lines.add("Known repositories:");
        for (var repo : repos) {
            lines.add("  " + OutputFormat.oneLine(String.valueOf(repo.getPath()))
                    + "  (" + OutputFormat.oneLine(String.valueOf(repo.sourceDescription())) + ")");
        }
        return lines;
    }
}
