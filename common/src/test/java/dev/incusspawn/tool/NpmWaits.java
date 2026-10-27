package dev.incusspawn.tool;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Records the waits {@link NpmGlobalInstall} makes before retrying the registry instead of
 * sleeping them, until closed. Public for the {@code cli} tests that reach an npm install.
 */
public final class NpmWaits implements AutoCloseable {

    private final DownloadCache.Pause previous = NpmGlobalInstall.pause;
    private final List<Duration> waited = new ArrayList<>();

    public NpmWaits() {
        NpmGlobalInstall.pause = waited::add;
    }

    public List<Duration> waited() {
        return List.copyOf(waited);
    }

    @Override
    public void close() {
        NpmGlobalInstall.pause = previous;
    }
}
