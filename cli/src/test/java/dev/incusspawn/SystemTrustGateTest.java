package dev.incusspawn;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every {@code HttpClient} isx builds trusts the system CA store, through
 * {@code SystemTrust.httpClient()}, and any other TLS client through {@code SystemTrust.sslContext()}.
 * A plain {@code HttpClient.newBuilder()} or default socket factory in a native binary trusts only
 * the JDK store baked in at build time, so behind TLS inspection it cannot verify a token or
 * download a base image while curl and the proxy can (#1437). A client that only ever speaks plain
 * HTTP says so with a {@value #MARKER} comment on that same line.
 */
class SystemTrustGateTest {

    private static final Pattern PLAIN_CLIENT = Pattern.compile(
            "HttpClient\\s*\\.\\s*new(Builder|HttpClient)\\s*\\(|SSL(Context|SocketFactory|Factory)\\s*\\.\\s*getDefault\\s*\\(|HttpsURLConnection");
    private static final String MARKER = "plain HTTP:";

    @Test
    void everyHttpClientTrustsTheSystemStore() throws IOException {
        var offenders = new StringBuilder();
        for (var root : new String[] {"src/main/java", "../common/src/main/java", "../proxy/src/main/java"}) {
            try (Stream<Path> files = Files.walk(Path.of(root))) {
                for (var file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("SystemTrust.java")) continue;
                    var lines = Files.readAllLines(file);
                    for (int i = 0; i < lines.size(); i++) {
                        if (PLAIN_CLIENT.matcher(lines.get(i)).find() && !lines.get(i).contains(MARKER)) {
                            offenders.append(file).append(':').append(i + 1).append('\n');
                        }
                    }
                }
            }
        }
        assertEquals("", offenders.toString(),
                "build HttpClients with SystemTrust.httpClient(), other TLS clients with SystemTrust.sslContext();"
                        + " a plain HTTP client says so with a '// " + MARKER + "' comment");
    }
}
