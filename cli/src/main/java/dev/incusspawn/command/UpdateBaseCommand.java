package dev.incusspawn.command;

import dev.incusspawn.baseimage.BaseImageReleases;
import dev.incusspawn.baseimage.BaseImageReleases.Checksums;
import dev.incusspawn.baseimage.BaseImageReleases.Release;
import dev.incusspawn.config.ImageDef;
import dev.incusspawn.util.BuildOutput;
import dev.incusspawn.util.OutputFormat;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqObject;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.yaml.JqYaml;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.option.Argument;
import org.aesh.command.option.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@CommandDefinition(
        name = "update-base",
        description = "Check for and install base image updates",
        generateHelp = true
)
public class UpdateBaseCommand extends BaseCommand {

    BaseImageReleases releases;

    @Argument(required = false, description = "Release tag to pin (e.g. fedora-44-v2)")
    String targetTag;

    @Option(name = "list", hasValue = false, description = "List available versions")
    boolean listOnly;

    @Option(name = "latest", hasValue = false, description = "Track the latest version (remove any pin)")
    boolean useLatest;

    // Output for scripts (#1036): see OutputFormat for what plain and json promise.
    @Option(name = "format", description = "Output format of --list: table (default), plain or json")
    String format;

    @Override
    protected CommandResult doExecute() throws Exception {
        var outputFormat = OutputFormat.parse(format);
        if (outputFormat != OutputFormat.TABLE && !listOnly) {
            throw new IllegalArgumentException("--format applies to --list only");
        }
        var defs = ImageDef.loadAll();
        var minimal = defs.get("tpl-minimal");
        if (minimal == null) {
            System.err.println("Template tpl-minimal not found.");
            return CommandResult.valueOf(1);
        }

        releases = BaseImageReleases.fromImageUrl(minimal.getImageUrl());
        if (releases == null) {
            System.err.println("Template tpl-minimal has no trackable GitHub base image URL.");
            return CommandResult.valueOf(1);
        }

        if (outputFormat != OutputFormat.TABLE) {
            List<Release> available;
            try {
                available = releases.fetchReleases();
            } catch (IOException e) {
                System.err.println("Failed to fetch releases: " + e.getMessage());
                return CommandResult.valueOf(1);
            }
            outputFormat.print(System.out, releaseRecords(available, minimal.getImageTag(), minimal.isPinned()));
            return CommandResult.SUCCESS;
        }

        BuildOutput.header("Checking for base image updates");
        var currentTag = minimal.getImageTag();
        var isPinned = minimal.isPinned();
        BuildOutput.step("Current base image: " + (currentTag != null ? currentTag : "unknown")
                + (isPinned ? " [pinned]" : ""));

        BuildOutput.stepStart("Fetching available releases...");
        List<Release> available;
        try {
            available = releases.fetchReleases();
        } catch (IOException e) {
            BuildOutput.stepFail("Failed to fetch releases: " + e.getMessage());
            return CommandResult.valueOf(1);
        }
        BuildOutput.stepDone();

        if (available.isEmpty()) {
            System.out.println("No releases found.");
            return CommandResult.SUCCESS;
        }

        if (listOnly) {
            printReleaseList(available, currentTag);
            return CommandResult.SUCCESS;
        }

        // Explicit tag argument → pin to that version
        if (targetTag != null) {
            return pinToTag(targetTag, available, minimal);
        }

        // --latest flag → track latest, remove any pin
        if (useLatest) {
            return trackLatest(available.get(0).tag());
        }

        // Interactive mode
        return interactive(available, minimal, currentTag);
    }

    private CommandResult trackLatest(String latestTag) throws IOException {
        var overridePath = ImageDef.userImagesDir().resolve("minimal.yaml");
        if (Files.deleteIfExists(overridePath)) {
            BuildOutput.note("Removed version pin.");
        }
        BuildOutput.success("Now tracking the latest base image.");
        BuildOutput.note("The newest release (" + latestTag
                + ") will be resolved and installed on the next 'isx build tpl-minimal'.");
        return CommandResult.SUCCESS;
    }

    CommandResult pinToTag(String tag, List<Release> available, ImageDef current) throws IOException {
        var selected = available.stream()
                .filter(r -> r.tag().equals(tag))
                .findFirst()
                .orElse(null);
        if (selected == null) {
            System.err.println("Release '" + tag + "' not found.");
            printReleaseList(available, current.getImageTag());
            return CommandResult.valueOf(1);
        }

        // A pin of a release without a usable VM image leaves vm_image_url out; the next pin
        // takes it from the built-in definition again, so that loss does not carry over. Only
        // for a definition on the built-in's images: one of the user's own, which may leave the
        // VM image out on purpose or name another repository, is pinned as it is.
        if (current.getVmImageUrl() == null) {
            var builtin = ImageDef.loadBuiltinByName("tpl-minimal");
            if (builtin != null && builtin.getVmImageUrl() != null
                    && java.util.Objects.equals(builtin.getImageUrl(), current.getImageUrl())) {
                current.setVmImageUrl(builtin.getVmImageUrl()); // this command's own copy, loaded for it
            }
        }
        BuildOutput.stepStart("Fetching checksums for " + selected.tag() + "...");
        var checksums = releases.fetchChecksums(selected, current.getImageUrl(), current.getVmImageUrl());
        if (checksums == null) {
            BuildOutput.stepFail("No readable SHA256SUMS in release " + selected.tag() + ".");
            return CommandResult.valueOf(1);
        }
        // A pin is used on any host it is copied to: an arch it has no checksum for would
        // download unverified there.
        if (!BaseImageReleases.coversEveryArch(checksums.container())) {
            BuildOutput.stepFail("Release " + selected.tag() + " does not list a checksum for "
                    + BaseImageReleases.assetName(current.getImageUrl(), selected.tag(), "<arch>")
                    + " on every architecture.");
            return CommandResult.valueOf(1);
        }
        BuildOutput.stepDone();

        writeUserOverride(current, selected.tag(), checksums);
        BuildOutput.success("Pinned base image to " + selected.tag() + ".");
        BuildOutput.note("The new base image will be downloaded on the next 'isx build tpl-minimal'.");
        return CommandResult.SUCCESS;
    }

    private CommandResult interactive(List<Release> available, ImageDef current, String currentTag) throws IOException {
        printReleaseList(available, currentTag);

        var latest = available.get(0);
        if (latest.tag().equals(currentTag) && !current.isPinned()) {
            BuildOutput.success("Already tracking the latest version.");
            return CommandResult.SUCCESS;
        }

        var console = System.console();
        if (console == null) {
            System.err.println("No console available. Use --latest or specify a tag.");
            return CommandResult.valueOf(1);
        }

        System.out.println("\nWhat would you like to do?");
        System.out.println("  1) Track latest (" + latest.tag() + ") — always installs the newest release on build");
        System.out.println("  2) Pin a specific version");
        System.out.println("  3) Cancel");
        System.out.print("Choice [1-3]: ");

        var choice = console.readLine();
        if (choice == null) return CommandResult.SUCCESS;
        choice = choice.strip();

        return switch (choice) {
            case "1" -> trackLatest(latest.tag());
            case "2" -> {
                System.out.print("Enter version tag: ");
                var tag = console.readLine();
                if (tag == null || tag.isBlank()) {
                    System.out.println("Aborted.");
                    yield CommandResult.SUCCESS;
                }
                yield pinToTag(tag.strip(), available, current);
            }
            default -> {
                System.out.println("Aborted.");
                yield CommandResult.SUCCESS;
            }
        };
    }

    void writeUserOverride(ImageDef current, String tag, Checksums checksums) throws IOException {
        var dir = ImageDef.userImagesDir();
        Files.createDirectories(dir);
        var overridePath = dir.resolve("minimal.yaml");

        var yaml = new StringBuilder();
        yaml.append("name: tpl-minimal\n");
        // The pin replaces the built-in definition, not only its image: keep what it removes and masks.
        // Through the YAML writer, which quotes what needs it: a dnf glob such as *-langpack-* bare
        // is an alias, and a description may hold ": ", " #" or a newline.
        var kept = JqObject.builder(3);
        if (current.getDescription() != null) kept.put("description", JqString.of(current.getDescription()));
        if (!current.getRemovePackages().isEmpty()) kept.put("remove_packages", strings(current.getRemovePackages()));
        if (!current.getMaskServices().isEmpty()) kept.put("mask_services", strings(current.getMaskServices()));
        var keptObject = kept.build();
        if (keptObject.size() > 0) yaml.append(JqYaml.toYaml(keptObject));
        yaml.append("image: ").append(current.getImage()).append('\n');
        yaml.append("image_url: ").append(current.getImageUrl()).append('\n');
        // A VM image is only pinned with its checksums for every arch: without them it would
        // download unverified on the arch that has none.
        var vm = current.getVmImageUrl() != null && BaseImageReleases.coversEveryArch(checksums.vm());
        if (vm) {
            yaml.append("vm_image_url: ").append(current.getVmImageUrl()).append('\n');
        } else if (current.getVmImageUrl() != null) {
            BuildOutput.warn("Release " + tag + " has no VM image this isx can use;"
                    + " VM templates built on this pin start from the Incus image server instead.");
        }
        yaml.append("image_tag: ").append(tag).append('\n');
        yaml.append("pinned: true\n");
        appendShaBlock(yaml, "image_sha256", checksums.container());
        if (vm) {
            appendShaBlock(yaml, "vm_image_sha256", checksums.vm());
        }

        Files.writeString(overridePath, yaml.toString());
        BuildOutput.note("Wrote " + overridePath);
    }

    private static JqArray strings(List<String> items) {
        var array = JqArray.arrayBuilder();
        items.forEach(i -> array.add(JqString.of(i)));
        return array.build();
    }

    private static void appendShaBlock(StringBuilder yaml, String key, Map<String, String> shas) {
        yaml.append(key).append(":\n");
        shas.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> yaml.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append('\n'));
    }

    private void printReleaseList(List<Release> available, String currentTag) {
        System.out.println("\nAvailable base images:");
        for (int i = 0; i < available.size(); i++) {
            var r = available.get(i);
            var markers = new ArrayList<String>();
            if (i == 0) markers.add("latest");
            if (r.tag().equals(currentTag)) markers.add("current");
            var suffix = markers.isEmpty() ? "" : "  [" + String.join(", ", markers) + "]";
            System.out.println("  " + r.tag() + "  " + r.date() + suffix);
        }
    }

    /**
     * The fields of {@code isx update-base --list --format=plain|json}, newest first, in order:
     * add to the end, never rename. {@code date} is the release's ISO-8601 publication date;
     * {@code pinned} marks the current release when it is pinned, so builds never move past it.
     * {@code listed} is whether the release is among those fetched (the newest published ones):
     * a current base image that is not -- older than all of them, unpublished, or deleted --
     * gets a record of its own, last, with {@code listed} false and no date, so a script always
     * finds the current one. Not being listed says nothing about its age.
     */
    static List<Map<String, Object>> releaseRecords(List<Release> available, String currentTag, boolean pinned) {
        var records = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < available.size(); i++) {
            var r = available.get(i);
            var record = new LinkedHashMap<String, Object>();
            record.put("tag", r.tag());
            record.put("date", r.date().isEmpty() ? null : r.date());
            record.put("latest", i == 0);
            record.put("current", r.tag().equals(currentTag));
            record.put("pinned", pinned && r.tag().equals(currentTag));
            record.put("listed", true);
            records.add(record);
        }
        if (currentTag != null && available.stream().noneMatch(r -> r.tag().equals(currentTag))) {
            var record = new LinkedHashMap<String, Object>();
            record.put("tag", currentTag);
            record.put("date", null);
            record.put("latest", false);
            record.put("current", true);
            record.put("pinned", pinned);
            record.put("listed", false);
            records.add(record);
        }
        return records;
    }
}
