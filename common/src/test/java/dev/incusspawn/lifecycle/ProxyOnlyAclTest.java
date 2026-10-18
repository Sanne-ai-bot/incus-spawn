package dev.incusspawn.lifecycle;

import com.fasterxml.jackson.databind.JsonNode;
import dev.incusspawn.config.NetworkMode;
import dev.incusspawn.incus.FakeIncusDaemon;
import dev.incusspawn.incus.IncusException;
import dev.incusspawn.incus.JacksonTrees;
import dev.incusspawn.incus.MachineType;
import dev.incusspawn.incus.Metadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code --proxy-only} is enforced by an Incus network ACL on the instance's NIC, on the host
 * (#1137), not by iptables rules inside the guest, which its root user could flush and which were
 * gone after any restart (#1311). The NIC carries it from the branch's one write, before any
 * start, so whoever starts the instance later starts it restricted, and a host whose Incus cannot
 * enforce it gets no proxy-only instance at all.
 */
@ExtendWith(TempHome.class)
@ExtendWith(LinuxBranching.class)
class ProxyOnlyAclTest {

    private static final String GATEWAY = "10.166.11.1";

    private static FakeIncusDaemon template() {
        return new FakeIncusDaemon().container("tpl-dev",
                Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev"));
    }

    /** Branch {@code dev-2} from {@code source} without starting it, as {@code --no-start} does. */
    private static void branch(FakeIncusDaemon daemon, String source, NetworkMode mode) {
        var request = new BranchFlow.Request(source, "dev-2", false, false, mode,
                null, null, null, null, List.of(), false, Map.of());
        // A non-airgapped branch checks the host's proxy first; this host may not run one.
        var original = BranchFlow.proxyHealthCheck;
        BranchFlow.proxyHealthCheck = i -> true;
        try {
            BranchFlow.create(daemon.client(), BranchFlow.preflight(daemon.client(), request, Map.of()));
        } finally {
            BranchFlow.proxyHealthCheck = original;
        }
    }

    private static JsonNode nic(FakeIncusDaemon daemon, String name) {
        return daemon.instance(name).path("expanded_devices").path("eth0");
    }

    private static void assertRestricted(JsonNode nic) {
        assertEquals(ProxyOnlyAcl.NAME, nic.path("security.acls").asText(), nic::toString);
        assertEquals("drop", nic.path("security.acls.default.egress.action").asText(), nic::toString);
        assertEquals("allow", nic.path("security.acls.default.ingress.action").asText(), nic::toString);
    }

    /** The ACL's egress rules, each as "protocol destination ports". */
    private static List<String> allowed(FakeIncusDaemon daemon) {
        var acl = daemon.networkAcl(ProxyOnlyAcl.NAME);
        assertTrue(acl != null, "no ACL was written");
        assertEquals(0, acl.path("ingress").size(), acl::toString);
        var rules = new ArrayList<String>();
        for (var rule : acl.path("egress")) {
            assertEquals("allow", rule.path("action").asText(), rule::toString);
            assertEquals("enabled", rule.path("state").asText(), rule::toString);
            rules.add(rule.path("protocol").asText() + " " + rule.path("destination").asText() + " "
                    + rule.path("destination_port").asText(rule.path("icmp_type").asText()));
        }
        return rules;
    }

    @Test
    void aProxyOnlyBranchIsRestrictedOnTheHostBeforeItIsEverStarted() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);

        assertRestricted(nic(daemon, "dev-2"));
        assertEquals(List.of("tcp " + GATEWAY + " 443,18443,18080", "udp " + GATEWAY + " 53",
                "tcp " + GATEWAY + " 53", "icmp4 " + GATEWAY + " 8"), allowed(daemon));
        // In the branch's one write, not a write of its own
        assertEquals(1, daemon.requests().stream().filter(r -> r.equals("PATCH /1.0/instances/dev-2")).count(),
                () -> String.join("\n", daemon.requests()));
        assertEquals(List.of(), daemon.stateActions(), "--no-start");
    }

    @Test
    void aHostWhoseIncusCannotEnforceItGetsNoProxyOnlyBranch() {
        var daemon = template().firewall("xtables");

        var e = assertThrows(BranchFlow.BranchException.class,
                () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));

        assertTrue(e.getMessage().contains("nftables") && e.getMessage().contains("xtables"), e.getMessage());
        assertFalse(daemon.client().exists("dev-2"), "refused before anything was created");
        assertNull(daemon.networkAcl(ProxyOnlyAcl.NAME));
    }

    @Test
    void aBranchWithNetworkOfAProxyOnlyInstanceIsNotRestricted() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);
        daemon.client().rename("dev-2", "dev-1");

        branch(daemon, "dev-1", NetworkMode.FULL);

        var nic = nic(daemon, "dev-2");
        for (var key : ProxyOnlyAcl.NIC_KEYS.keySet()) assertFalse(nic.has(key), nic::toString);
        assertEquals("true", nic.path("security.ipv4_filtering").asText(), "the rest of the NIC is kept");
        assertRestricted(nic(daemon, "dev-1"));
    }

    /** A proxy-only instance from before #1137: its rules were inside the guest, and its NIC has no ACL. */
    private static FakeIncusDaemon unenforced(String status) {
        var daemon = new FakeIncusDaemon().instance("box", "container", status, Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        return daemon.ipFiltering("box", "true");
    }

    @Test
    void aProxyOnlyInstanceBranchedBeforeIsRestrictedBeforeItsNextStart() {
        var daemon = unenforced("Stopped");
        var said = new ArrayList<String>();

        // FakeIncusDaemon serves no exec, so the readiness wait after the start can only time out
        assertThrows(IncusException.class, () -> InstanceLifecycle.ensureReady(daemon.clientWithShortReadyWait(), "box",
                JacksonTrees.fromJsonNode(daemon.instance("box")), MachineType.CONTAINER, said::add));

        assertRestricted(nic(daemon, "box"));
        assertEquals(4, allowed(daemon).size());
        var requests = daemon.requests();
        int write = requests.lastIndexOf("PATCH /1.0/instances/box");
        int start = requests.indexOf("PUT /1.0/instances/box/state");
        assertTrue(write >= 0 && start > write, "restricted before the start:\n" + String.join("\n", requests));
        assertTrue(said.stream().anyMatch(s -> s.contains("now enforced on the host")), said::toString);
    }

    @Test
    void theAclAllowsTheBridgesGatewayNotOneAnInstanceRecordedBeforeASubnetMove() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Running", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, "10.99.0.1"))
                .ipFiltering("box", "true");

        InstanceLifecycle.ensureReady(daemon.client(), "box", JacksonTrees.fromJsonNode(daemon.instance("box")),
                MachineType.CONTAINER, s -> {});

        // Shared by every proxy-only instance: a stale record must not take the proxy from the others
        assertTrue(allowed(daemon).stream().allMatch(rule -> rule.contains(" " + GATEWAY + " ")), allowed(daemon)::toString);
    }

    @Test
    void oneWhoseNetworkIsNotOnTheBridgeIsNotUsed() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        daemon.device("box", "eth0", Map.of("type", "nic", "network", "lanbr0", "name", "eth0"));

        assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", JacksonTrees.fromJsonNode(daemon.instance("box")), MachineType.CONTAINER, s -> {}));
        assertEquals(List.of(), daemon.stateActions());
    }

    @Test
    void oneWithNoNetworkAtAllIsWithinTheMode() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name()));
        daemon.device("box", "eth0", Map.of("type", "none"));

        InstanceLifecycle.prepareForStart(daemon.client(), "box",
                JacksonTrees.fromJsonNode(daemon.instance("box")), w -> fail(w));
    }

    @Test
    void aRunningOneIsRestrictedBeforeAShellOpensInIt() {
        var daemon = unenforced("Running");

        InstanceLifecycle.ensureReady(daemon.client(), "box", JacksonTrees.fromJsonNode(daemon.instance("box")),
                MachineType.CONTAINER, s -> {});

        assertRestricted(nic(daemon, "box"));
        assertEquals(List.of(), daemon.stateActions());
    }

    @Test
    void oneThatCannotBeRestrictedIsNotStarted() {
        var daemon = unenforced("Stopped").firewall("xtables");

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", JacksonTrees.fromJsonNode(daemon.instance("box")), MachineType.CONTAINER, s -> {}));

        assertTrue(e.getMessage().contains("nftables"), e.getMessage());
        assertEquals(List.of(), daemon.stateActions());
        assertFalse(nic(daemon, "box").has("security.acls"));
    }

    @Test
    void aRestrictedOneOnAHostThatMovedToXtablesIsRefusedWithTheReason() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);
        daemon.firewall("xtables");

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "dev-2", JacksonTrees.fromJsonNode(daemon.instance("dev-2")), MachineType.CONTAINER, s -> {}));

        assertTrue(e.getMessage().contains("cannot be enforced on the host"), e.getMessage());
        assertTrue(e.getMessage().contains("uses xtables"), e.getMessage());
        assertEquals(List.of(), daemon.stateActions());
        assertRestricted(nic(daemon, "dev-2"));
    }

    @Test
    void aRestrictedInstanceCostsNoRequestBeyondTheReadTheRepairsShare() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);
        int before = daemon.requests().size();

        InstanceLifecycle.prepareForStart(daemon.client(), "dev-2",
                JacksonTrees.fromJsonNode(daemon.instance("dev-2")), w -> fail(w));

        assertEquals(List.of(), daemon.requests().subList(before, daemon.requests().size()));
    }

    @Test
    void anInstanceThatIsNotProxyOnlyIsLeftAlone() {
        var daemon = new FakeIncusDaemon().container("box", new HashMap<>()).ipFiltering("box", "true");

        InstanceLifecycle.prepareForStart(daemon.client(), "box",
                JacksonTrees.fromJsonNode(daemon.instance("box")), w -> fail(w));

        assertFalse(nic(daemon, "box").has("security.acls"));
        assertNull(daemon.networkAcl(ProxyOnlyAcl.NAME));
    }

    @Test
    void theSpoofingFallbackNeverStripsARestrictedNic() {
        var daemon = template();
        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);

        assertFalse(InstanceNetwork.disableIpFiltering(daemon.client(), "dev-2", w -> fail(w)));

        assertEquals("true", nic(daemon, "dev-2").path("security.ipv4_filtering").asText());
        assertRestricted(nic(daemon, "dev-2"));
    }

    /** Only the bridge NIC carries the ACL: a second one would have full egress (#1137). */
    @Test
    void aSourceWithASecondNicGetsNoProxyOnlyBranch() {
        var daemon = template();
        daemon.device("tpl-dev", "eth1", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0", "name", "eth1"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("eth1"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    @Test
    void aSourceWithASecondNicStillBranchesWithFullNetwork() {
        var daemon = template();
        daemon.device("tpl-dev", "eth1", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0", "name", "eth1"));

        branch(daemon, "tpl-dev", NetworkMode.FULL);

        var branched = daemon.instance("dev-2").path("expanded_devices");
        assertEquals("macvlan", branched.path("eth1").path("nictype").asText(), branched::toString);
        for (var key : ProxyOnlyAcl.NIC_KEYS.keySet()) assertTrue(branched.path("eth0").path(key).isMissingNode(), branched::toString);
    }

    @Test
    void aProxyOnlyInstanceThatGainedASecondNicIsNotStarted() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        var restricted = new HashMap<String, String>(ProxyOnlyAcl.NIC_KEYS);
        restricted.putAll(Map.of("type", "nic", "network", "incusbr0", "name", "eth0"));
        daemon.device("box", "eth0", restricted);
        daemon.device("box", "eth1", Map.of("type", "nic", "network", "incusbr0", "name", "eth1"));

        var e = assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", JacksonTrees.fromJsonNode(daemon.instance("box")), MachineType.CONTAINER, s -> {}));
        assertTrue(e.getMessage().contains("eth1"), e::getMessage);
        assertEquals(List.of(), daemon.stateActions());
    }

    /** An airgapped source's NICs are masked, but the branch puts the profile's back: both count. */
    @Test
    void anAirgappedSourceWhoseProfileHasASecondNicGetsNoProxyOnlyBranch() {
        var daemon = new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "eth1", Map.of("type", "nic", "nictype", "macvlan", "parent", "enp1s0", "name", "eth1"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default")));
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev",
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name()));
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));
        daemon.device("tpl-dev", "eth1", Map.of("type", "none"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("eth1"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    /** Unmasking puts a profile's InfiniBand device back too, so it counts as a second one. */
    @Test
    void anAirgappedSourceWhoseProfileHasInfinibandGetsNoProxyOnlyBranch() {
        var daemon = new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default")));
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev",
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name(), Metadata.AIRGAP_MASKED, "eth0,ib0"));
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));
        daemon.device("tpl-dev", "ib0", Map.of("type", "none"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("ib0"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    /** A mask the template set itself is not airgap's, so unmasking leaves it and it does not count. */
    @Test
    void anAirgappedSourceWhoseTemplateMasksTheProfilesInfinibandGetsAProxyOnlyBranch() {
        var daemon = new FakeIncusDaemon().profile("default", Map.of(
                "eth0", Map.of("type", "nic", "network", "incusbr0", "name", "eth0"),
                "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"),
                "root", Map.of("type", "disk", "path", "/", "pool", "default")));
        daemon.container("tpl-dev", Map.of(Metadata.TYPE, Metadata.TYPE_BASE, Metadata.PROFILE, "tpl-dev",
                Metadata.NETWORK_MODE, NetworkMode.AIRGAP.name(), Metadata.AIRGAP_MASKED, "eth0"));
        daemon.device("tpl-dev", "eth0", Map.of("type", "none"));
        daemon.device("tpl-dev", "ib0", Map.of("type", "none"));

        branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY);

        assertRestricted(nic(daemon, "dev-2"));
        assertEquals("none", daemon.instance("dev-2").path("devices").path("ib0").path("type").asText());
    }

    /** An InfiniBand device reaches a network without any NIC, so it counts as a second one. */
    @Test
    void aSourceWithAnInfinibandDeviceGetsNoProxyOnlyBranch() {
        var daemon = template();
        daemon.device("tpl-dev", "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"));

        var e = assertThrows(BranchFlow.BranchException.class, () -> branch(daemon, "tpl-dev", NetworkMode.PROXY_ONLY));
        assertTrue(e.getMessage().contains("ib0"), e::getMessage);
        assertFalse(daemon.requests().stream().anyMatch(r -> r.startsWith("POST /1.0/instances")),
                () -> String.join("\n", daemon.requests()));
    }

    /** With no bridge NIC, an InfiniBand device is still a way out: the instance is not started. */
    @Test
    void aProxyOnlyInstanceWhoseOnlyNetworkIsInfinibandIsNotStarted() {
        var daemon = new FakeIncusDaemon().instance("box", "container", "Stopped", Map.of(
                Metadata.NETWORK_MODE, NetworkMode.PROXY_ONLY.name(), Metadata.PROXY_GATEWAY, GATEWAY));
        daemon.device("box", "eth0", Map.of("type", "none"));
        daemon.device("box", "ib0", Map.of("type", "infiniband", "nictype", "physical", "parent", "ibp1s0"));

        assertThrows(ProxyOnlyAcl.NotEnforceableException.class, () -> InstanceLifecycle.ensureReady(
                daemon.client(), "box", JacksonTrees.fromJsonNode(daemon.instance("box")), MachineType.CONTAINER, s -> {}));
        assertEquals(List.of(), daemon.stateActions());
    }
}
