package dev.incusspawn.config;

import dev.incusspawn.proxy.ProxyConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code host-routes:} (#936): each entry is checked on its own, and a wrong one is dropped with a
 * warning naming it while the valid ones are kept.
 */
class HostRoutesTest {

    private final List<String> warnings = new ArrayList<>();

    private java.util.Map<String, HostRoutes.Route> routes(String yaml) throws Exception {
        return Jjq.spawnConfig(yaml).hostRoutes().routes(ProxyConfig.interceptedDomains(Set.of("*.example.com")),
                warnings::add);
    }

    @Test
    void noSectionIsNoRoutes() throws Exception {
        assertEquals(java.util.Map.of(), routes("{}"));
        assertFalse(Jjq.toYaml(new SpawnConfig()).contains("host-routes"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void aValidRouteIsKeptAsWritten() throws Exception {
        var routes = routes("""
                host-routes:
                  Work.isx.internal:
                    target: 127.0.0.1:7070
                    templates: [tpl-isx-agent]
                  v6.isx.internal:
                    target: "[::1]:8080"
                  local.isx.internal:
                    target: localhost:1
                """);
        assertEquals(List.of(), warnings);
        assertEquals(new HostRoutes.Route("work.isx.internal", "127.0.0.1", 7070, List.of("tpl-isx-agent")),
                routes.get("work.isx.internal"));
        assertEquals(new HostRoutes.Route("v6.isx.internal", "::1", 8080, List.of()), routes.get("v6.isx.internal"));
        assertEquals(new HostRoutes.Route("local.isx.internal", "127.0.0.1", 1, List.of()), routes.get("local.isx.internal"),
                "localhost is never looked up");
    }

    @Test
    void aLoopbackLiteralIsStoredCanonicalSoItIsNeverLookedUp() throws Exception {
        // Netty reads neither 127.1 nor 2130706433 as an address, and would resolve them as names
        var routes = routes("""
                host-routes:
                  short.isx.internal: {target: 127.1:7070}
                  number.isx.internal: {target: 2130706433:7070}
                  long.isx.internal: {target: "[0:0:0:0:0:0:0:1]:7070"}
                  scoped.isx.internal: {target: "[::1%lo]:7070"}
                """);
        assertEquals("::1", routes.get("long.isx.internal").host());
        assertNull(routes.get("scoped.isx.internal"), "a scoped literal is refused");
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("scoped.isx.internal"), warnings.getFirst());
        assertEquals("127.0.0.1", routes.get("short.isx.internal").host());
        assertEquals("127.0.0.1", routes.get("number.isx.internal").host());
    }

    @Test
    void aTargetOffThisHostsLoopbackIsDropped() throws Exception {
        var routes = routes("""
                host-routes:
                  lan.isx.internal: {target: 192.168.1.5:7070}
                  name.isx.internal: {target: controller.lan:7070}
                  any.isx.internal: {target: 0.0.0.0:7070}
                  noport.isx.internal: {target: 127.0.0.1}
                  zero.isx.internal: {target: 127.0.0.1:0}
                  high.isx.internal: {target: 127.0.0.1:65536}
                  ok.isx.internal: {target: 127.0.0.2:7070}
                """);
        assertEquals(Set.of("ok.isx.internal"), routes.keySet());
        assertEquals(6, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("lan.isx.internal"), warnings.getFirst());
    }

    @Test
    void aNameOutsideInternalOrOverlappingAnInterceptedDomainIsDropped() throws Exception {
        var routes = routes("""
                host-routes:
                  work.example.org: {target: 127.0.0.1:1}
                  internal: {target: 127.0.0.1:1}
                  mcp.isx.internal: {target: 127.0.0.1:1}
                  isx.internal: {target: 127.0.0.1:1}
                  x.example.com: {target: 127.0.0.1:1}
                  ok.isx.internal: {target: 127.0.0.1:1}
                """);
        assertEquals(Set.of("ok.isx.internal"), routes.keySet());
        assertEquals(5, warnings.size(), warnings.toString());
        assertTrue(warnings.get(3).contains("mcp.isx.internal"), "a name above isx mcp's is refused too: " + warnings.get(3));
    }

    @Test
    void aRouteUnderAnotherIsDropped() throws Exception {
        var routes = routes("""
                host-routes:
                  deep.work.isx.internal: {target: 127.0.0.1:1}
                  work.isx.internal: {target: 127.0.0.1:2}
                """);
        assertEquals(Set.of("work.isx.internal"), routes.keySet());
        assertTrue(warnings.getFirst().contains("deep.work.isx.internal"), warnings.toString());
    }

    @Test
    void aMistypedEntryNeverWidensWhatItAdmits() throws Exception {
        var routes = routes("""
                host-routes:
                  typo.isx.internal: {target: 127.0.0.1:1, templtes: [tpl-a]}
                  empty.isx.internal: {target: 127.0.0.1:1, templates: []}
                  scalar.isx.internal: {target: 127.0.0.1:1, templates: tpl-a}
                  bare.isx.internal: 127.0.0.1:1
                """);
        assertEquals(java.util.Map.of(), routes);
        assertEquals(4, warnings.size(), warnings.toString());
    }

    @Test
    void aNameWrittenTwiceIsDroppedBothTimes() throws Exception {
        var routes = routes("""
                host-routes:
                  work.isx.internal: {target: 127.0.0.1:1, templates: [tpl-a]}
                  Work.isx.internal: {target: 127.0.0.1:1}
                """);
        assertEquals(java.util.Map.of(), routes);
        assertEquals(2, warnings.size(), warnings.toString());
    }

    @Test
    void aSectionThatIsNotAMappingIsIgnored() throws Exception {
        assertEquals(java.util.Map.of(), routes("host-routes: [work.isx.internal]"));
        assertEquals(1, warnings.size());
    }

    @Test
    void theSectionRoundTrips() throws Exception {
        var yaml = Jjq.toYaml(Jjq.spawnConfig("""
                host-routes:
                  work.isx.internal:
                    target: 127.0.0.1:7070
                """));
        assertEquals(Set.of("work.isx.internal"), Jjq.spawnConfig(yaml).hostRoutes()
                .routes(Set.of(), warnings::add).keySet());
    }

    @Test
    void projectLocalNeverAndTheFilterOtherwise() {
        var any = new HostRoutes.Route("a.isx.internal", "127.0.0.1", 1, List.of());
        var listed = new HostRoutes.Route("b.isx.internal", "127.0.0.1", 1, List.of("tpl-a"));
        assertTrue(any.admits("tpl-z", false));
        assertFalse(any.admits("tpl-z", true));
        assertTrue(listed.admits("tpl-a", false));
        assertFalse(listed.admits("tpl-a", true));
        assertFalse(listed.admits("tpl-b", false));
        assertFalse(listed.admits("", false));
    }
}
