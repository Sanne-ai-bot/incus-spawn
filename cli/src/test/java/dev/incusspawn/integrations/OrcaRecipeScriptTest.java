package dev.incusspawn.integrations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code integrations/orca/isx-orca-recipe} (#1720): Orca's environment-recipe contract over isx's own
 * commands. Run against a stand-in {@code isx} that records its arguments and answers as the real
 * one does, so what reaches Orca's stdout and what isx is asked to do are both pinned.
 */
class OrcaRecipeScriptTest {

    private static final Path SCRIPT = Path.of("..", "integrations", "orca", "isx-orca-recipe");
    private static final String RUNTIME = "orca-3f2a6c1e-0b5d-4e8f-9a7c-1d2e3f4a5b6c";

    @TempDir
    Path dir;

    private record Run(int exit, String out, String err, List<String> isx) {}

    @BeforeEach
    void needsTheHostTools() {
        for (var tool : List.of("bash", "jq", "sha256sum")) assumeTrue(onPath(tool), tool + " is not installed");
    }

    private static boolean onPath(String tool) {
        for (var d : System.getenv().getOrDefault("PATH", "").split(":")) {
            if (!d.isEmpty() && Files.isExecutable(Path.of(d, tool))) return true;
        }
        return false;
    }

    /** A stand-in isx: logs each call, prints {@code branchJson} for branch, fails when {@code fail} names the subcommand. */
    private void isx(String branchJson, String fail) throws Exception {
        var bin = Files.createDirectories(dir.resolve("bin"));
        var isx = bin.resolve("isx");
        Files.writeString(isx, """
                #!/bin/sh
                printf '%%s\\n' "$*" >> "$STATE/isx.log"
                [ "$1" = "%s" ] && { echo "Error: refused" >&2; exit 1; }
                case "$1" in
                  branch) echo "progress on stdout would corrupt Orca's" >&2; printf '%%s\\n' '%s' ;;
                  *) echo "isx $1 progress" ;;
                esac
                """.formatted(fail, branchJson));
        Files.setPosixFilePermissions(isx, PosixFilePermissions.fromString("rwxr-xr-x"));
    }

    private Run run(Map<String, String> env, String stdin, String... args) throws Exception {
        var cmd = new ArrayList<>(List.of("bash", SCRIPT.toString()));
        cmd.addAll(List.of(args));
        var input = Files.writeString(dir.resolve("stdin"), stdin);
        var pb = new ProcessBuilder(cmd).redirectInput(input.toFile())
                .redirectOutput(dir.resolve("out").toFile()).redirectError(dir.resolve("err").toFile());
        pb.environment().put("PATH", dir.resolve("bin") + ":" + System.getenv("PATH"));
        pb.environment().put("STATE", dir.toString());
        // Hermetic: never Orca's variables from the shell running the tests
        pb.environment().keySet().removeIf(key -> key.startsWith("ORCA_") || key.startsWith("ISX_"));
        pb.environment().putAll(env);
        var p = pb.start();
        assertTrue(p.waitFor(30, TimeUnit.SECONDS));
        var log = dir.resolve("isx.log");
        return new Run(p.exitValue(), Files.readString(dir.resolve("out")), Files.readString(dir.resolve("err")),
                Files.exists(log) ? Files.readAllLines(log) : List.of());
    }

    private static final String BRANCHED = "{\"name\":\"orca-NAME\",\"owner\":\"orca\",\"owner_id\":\"" + RUNTIME + "\","
            + "\"ssh_host\":\"orca-NAME\",\"ssh_user\":\"agentuser\",\"ssh_port\":22,"
            + "\"ssh_identity_file\":\"/home/u/.config/incus-spawn/ssh/id_ed25519\","
            + "\"ssh_proxy_command\":\"\\\"/usr/bin/isx\\\" ssh-proxy --start orca-NAME\",\"project_root\":\"/home/agentuser/app\"}";

    private static String name() throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(RUNTIME.getBytes());
        return "orca-" + java.util.HexFormat.of().formatHex(digest).substring(0, 12);
    }

    @Test
    void createBranchesForTheRuntimeAndPrintsOnlyOrcasResult() throws Exception {
        var name = name();
        isx(BRANCHED.replace("orca-NAME", name), "");
        var run = run(Map.of("ORCA_VM_INSTANCE_ID", RUNTIME), "", "create", "--from", "tpl-orca");

        assertEquals(0, run.exit(), run.err());
        assertEquals(List.of("destroy " + name + " --owner orca --owner-id " + RUNTIME,
                "branch " + name + " --from tpl-orca --owner orca --owner-id " + RUNTIME + " --format=json"), run.isx(),
                "an earlier attempt's instance is taken back first, only if it is still this runtime's");
        var result = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(run.out().strip());
        assertEquals(1, result.pathInt("schemaVersion", 0));
        var connection = result.path("connection");
        assertEquals("ssh", connection.pathText("type", ""));
        assertEquals("/home/agentuser/app", connection.pathText("projectRoot", ""));
        var target = connection.path("target");
        assertEquals(name, target.pathText("host", ""));
        assertEquals("agentuser", target.pathText("username", ""));
        assertEquals(22, target.pathInt("port", 0));
        assertEquals("\"/usr/bin/isx\" ssh-proxy --start " + name, target.pathText("proxyCommand", ""));
        assertEquals(name, result.path("userData").pathText("resourceId", ""));
        assertEquals(RUNTIME, result.path("userData").pathText("runtimeId", ""));
        assertEquals(1, run.out().strip().lines().count(), "one JSON object, nothing else on stdout");
    }

    @Test
    void aTemplateWithoutSshIsTakenBackAndRefused() throws Exception {
        var name = name();
        isx(BRANCHED.replace("orca-NAME", name).replace("\"ssh_host\":\"" + name + "\"", "\"ssh_host\":null"), "");
        var run = run(Map.of("ORCA_VM_INSTANCE_ID", RUNTIME), "", "create", "--from", "tpl-minimal");

        assertNotEquals(0, run.exit());
        assertEquals("", run.out());
        assertEquals("destroy " + name + " --owner orca --owner-id " + RUNTIME, run.isx().getLast());
        assertTrue(run.err().contains("has no SSH server: build it with the orca tool"), run.err());
    }

    /** Anything failing once the instance exists takes it back: Orca destroys only what a result named. */
    @Test
    void aBranchOutputThatCannotBeReadIsTakenBackAndSaidSo() throws Exception {
        var name = name();
        isx("not json", "");
        var run = run(Map.of("ORCA_VM_INSTANCE_ID", RUNTIME), "", "create", "--from", "tpl-orca");
        assertNotEquals(0, run.exit());
        assertEquals("", run.out());
        assertEquals("destroy " + name + " --owner orca --owner-id " + RUNTIME, run.isx().getLast());
        assertTrue(run.err().contains("could not read what isx branch printed"), run.err());
    }

    @Test
    void createRefusesWhatItCannotServe() throws Exception {
        isx(BRANCHED, "");
        assertTrue(run(Map.of("ORCA_VM_INSTANCE_ID", RUNTIME), "", "create").err().contains("name the template"));
        assertTrue(run(Map.of("ORCA_VM_INSTANCE_ID", RUNTIME, "ORCA_RECIPE_RESULT_SCHEMA_VERSION", "2"), "",
                "create", "--from", "tpl-orca").err().contains("only Orca's worktree mode"));
        assertTrue(run(Map.of("ORCA_VM_INSTANCE_ID", "$(id)"), "", "create", "--from", "tpl-orca")
                .err().contains("ORCA_VM_INSTANCE_ID"));
        assertEquals(List.of(), run(Map.of(), "", "create", "--from", "tpl-orca").isx(), "nothing branched for any of them");
    }

    private String payload(String mode, String instance) {
        return "{\"mode\":\"" + mode + "\",\"instanceId\":\"" + RUNTIME + "\",\"recipeResult\":{\"schemaVersion\":1,"
                + "\"connection\":{\"type\":\"ssh\",\"projectRoot\":\"/home/agentuser/app\",\"target\":{\"host\":\"" + instance + "\"}},"
                + "\"userData\":{\"provider\":\"incus-spawn\",\"resourceId\":\"" + instance + "\",\"runtimeId\":\"" + RUNTIME + "\"}}}";
    }

    @Test
    void suspendResumeAndDestroyActOnTheInstanceThePayloadNames() throws Exception {
        var name = name();
        isx(BRANCHED, "");
        var suspend = run(Map.of(), payload("suspend", name), "suspend");
        var resume = run(Map.of(), payload("resume", name), "resume");
        var destroy = run(Map.of(), payload("destroy", name), "destroy");

        assertEquals(0, suspend.exit(), suspend.err());
        assertEquals("", suspend.out(), "isx's progress goes to stderr");
        assertEquals(0, resume.exit(), resume.err());
        var resumed = io.hyperfoil.tools.jjq.value.JqValues.parseStrict(resume.out().strip());
        assertEquals(name, resumed.path("userData").pathText("resourceId", ""), "resume prints the result back");
        assertEquals(0, destroy.exit(), destroy.err());
        assertEquals(List.of("stop " + name, "start " + name, "destroy " + name + " --owner orca --owner-id " + RUNTIME),
                destroy.isx());
    }

    /** A payload cannot point isx at an instance this recipe did not make, nor pass for another mode. */
    @Test
    void aPayloadThatIsNotOneCreateAnsweredIsRefused() throws Exception {
        isx(BRANCHED, "");
        var name = name();
        for (var bad : List.of(payload("destroy", "my-own-box"), payload("destroy", "orca-" + "0".repeat(12)),
                payload("suspend", name), payload("destroy", name).replace("incus-spawn", "someone-else"), "not json")) {
            var run = run(Map.of(), bad, "destroy");
            assertNotEquals(0, run.exit(), bad);
        }
        assertEquals(List.of(), run(Map.of(), "{}", "destroy").isx(), "isx was never asked");
    }

    @Test
    void aFailedIsxCommandFailsTheRecipe() throws Exception {
        isx(BRANCHED, "destroy");
        var run = run(Map.of(), payload("destroy", name()), "destroy");
        assertNotEquals(0, run.exit());
        assertTrue(run.err().contains("refused"), run.err());
    }
}
