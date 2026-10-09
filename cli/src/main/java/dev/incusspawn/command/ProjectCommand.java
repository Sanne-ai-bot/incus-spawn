package dev.incusspawn.command;

import dev.incusspawn.RuntimeServices;
import dev.incusspawn.config.ProjectConfig;
import dev.incusspawn.incus.IncusClient;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import dev.incusspawn.lifecycle.InstanceLifecycle;
import dev.incusspawn.util.BuildOutput;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.nio.file.Path;
import java.util.ArrayList;

import static dev.incusspawn.incus.Container.shellQuote;

@CommandDefinition(
        name = "project",
        description = "Manage project templates",
        generateHelp = true,
        groupCommands = {
                ProjectCommand.Create.class,
                ProjectCommand.Update.class
        }
)
public class ProjectCommand extends BaseCommand {

    @Override
    protected CommandResult doExecute() throws Exception {
        System.out.println(commandInvocation.getHelpInfo());
        return CommandResult.SUCCESS;
    }

    /**
     * Runs the project's pre-build, if it has one, as agentuser. Returns false, after reporting
     * it, when it failed.
     */
    static boolean preBuild(IncusClient incus, String name, String preBuild) {
        if (preBuild == null || preBuild.isBlank()) return true;
        BuildOutput.stepStart("Running pre-build: " + preBuild + "...");
        return GuestUpdate.finish(incus.execInContainer(name, "agentuser", preBuild), "pre-build");
    }

    @CommandDefinition(
            name = "create",
            description = "Create a project template from a parent base image",
            generateHelp = true
    )
    public static class Create extends BaseCommand {

        @Argument(required = true, description = "Name of the project template")
        String name;

        @Option(name = "config", description = "Path to incus-spawn.yaml (default: auto-detect from cwd)")
        Path configPath;

        @Override
        protected CommandResult doExecute() throws Exception {
            return CommandResult.valueOf(create(RuntimeServices.incus(), loadConfig()));
        }

        /**
         * Builds the template from {@code projectConfig}, returning the exit code. A failed repo
         * clone or pre-build, or anything thrown on the way, fails the command and deletes the
         * half-built template, so a project template that exists is one whose create completed
         * (as with {@code isx build}).
         */
        int create(IncusClient incus, ProjectConfig projectConfig) {
            var imageName = name != null ? name : projectConfig.getName();

            if (imageName == null || imageName.isBlank()) {
                System.err.println("Error: project name is required (either as argument or in incus-spawn.yaml 'name' field).");
                return 1;
            }

            var parent = projectConfig.getParent();

            if (!incus.exists(parent)) {
                System.err.println("Error: parent image '" + parent + "' does not exist. Run 'incus-spawn build " + parent + "' first.");
                return 1;
            }

            BuildOutput.header("Creating project template " + imageName);
            BuildOutput.note("Parent: " + parent);

            if (incus.exists(imageName)) {
                BuildOutput.note("'" + imageName + "' already exists — deleting and rebuilding.");
                incus.delete(imageName, true);
            }

            try {
                if (build(incus, imageName, parent, projectConfig)) return 0;
            } catch (RuntimeException e) {
                try {
                    incus.deleteIfExists(imageName);
                } catch (RuntimeException deleteFailed) {
                    e.addSuppressed(deleteFailed);
                }
                throw e;
            }
            BuildOutput.stepStart("Deleting the incomplete template...");
            incus.delete(imageName, true);
            BuildOutput.stepDone();
            System.err.println("Error: project template " + imageName + " was not created."
                    + " Fix the failure above and run 'isx project create' again.");
            return 1;
        }

        /** Builds and stops the template; false, after the failed step reported why, when one failed. */
        private static boolean build(IncusClient incus, String imageName, String parent, ProjectConfig projectConfig) {
            // Clone from parent
            var machineType = incus.machineType(parent);
            BuildOutput.stepStart("Cloning from " + parent + "...");
            incus.copy(parent, imageName);
            incus.start(imageName);
            incus.waitForReady(imageName, machineType);
            BuildOutput.stepDone();

            // Clone repos
            if (projectConfig.getRepos() != null) {
                for (var repo : projectConfig.getRepos()) {
                    BuildOutput.stepStart("Cloning " + repo + "...");
                    var cloned = incus.execInContainer(imageName, "agentuser", "git clone " + shellQuote(repo));
                    if (!GuestUpdate.finish(cloned, "git clone " + repo)) return false;
                }
            }

            if (!preBuild(incus, imageName, projectConfig.getPreBuild())) return false;

            InstanceLifecycle.tagMetadata(incus, imageName, Metadata.TYPE_PROJECT, parent);
            incus.configSet(imageName, Metadata.PROJECT, imageName);

            // Stop the template
            BuildOutput.stepStart("Stopping template...");
            incus.stop(imageName);
            BuildOutput.stepDone();

            BuildOutput.success("Project template " + imageName + " created.");
            return true;
        }

        private ProjectConfig loadConfig() {
            if (configPath != null) {
                return ProjectConfig.load(configPath);
            }
            var found = ProjectConfig.findInDirectory(Path.of("."));
            if (found != null) {
                return found;
            }
            System.err.println("Error: no incus-spawn.yaml found. Use --config to specify one.");
            System.exit(1);
            return null;
        }

    }

    @CommandDefinition(
            name = "update",
            description = "Update a project template (system packages, git repos, dependencies)",
            generateHelp = true
    )
    public static class Update extends BaseCommand {

        @Argument(required = true, description = "Name of the project template to update")
        String name;

        @Option(name = "config", description = "Path to incus-spawn.yaml")
        Path configPath;

        @Override
        protected CommandResult doExecute() throws Exception {
            var projectConfig = configPath != null ? ProjectConfig.load(configPath) : ProjectConfig.findInDirectory(Path.of("."));
            return CommandResult.valueOf(update(RuntimeServices.incus(), projectConfig));
        }

        /**
         * Updates the template, re-running {@code projectConfig}'s pre-build when there is one,
         * and returns the exit code. A config found in the working directory rather than named
         * with --config must name this template, or it may be another project's pre-build.
         */
        int update(IncusClient incus, ProjectConfig projectConfig) {
            if (!incus.exists(name)) {
                System.err.println("Error: image '" + name + "' does not exist.");
                return 1;
            }
            if (configPath == null && projectConfig != null && projectConfig.getName() != null && !projectConfig.getName().equals(name)) {
                System.err.println("Error: the incus-spawn.yaml found here is for project '" + projectConfig.getName()
                        + "', not '" + name + "'. Use --config to name the one for '" + name + "'.");
                return 1;
            }

            BuildOutput.header("Updating project template " + name);

            // Start if stopped
            var machineType = incus.machineType(name);
            incus.start(name);
            incus.waitForReady(name, machineType);

            var failedSteps = new ArrayList<String>();
            if (!GuestUpdate.system(incus, name)) failedSteps.add("system update");
            // Update globally installed npm packages (coding tools, etc.)
            if (!NpmUpdate.run(incus, name)) failedSteps.add("npm update");
            // Git fetch in all repos
            if (!GuestUpdate.gitRepos(incus, name)) failedSteps.add("git fetch");

            // Re-run pre-build if config available
            if (projectConfig != null && !preBuild(incus, name, projectConfig.getPreBuild())) {
                failedSteps.add("pre-build");
            }

            // Stop
            BuildOutput.stepStart("Stopping template...");
            incus.stop(name);
            BuildOutput.stepDone();

            if (!failedSteps.isEmpty()) {
                BuildOutput.warn("Failed: " + String.join(", ", failedSteps) + ". Re-run 'isx project update " + name + "' to retry.");
                return 1;
            }
            BuildOutput.success("Project template " + name + " updated.");
            return 0;
        }

    }
}
