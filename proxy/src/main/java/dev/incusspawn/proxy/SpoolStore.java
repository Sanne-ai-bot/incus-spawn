package dev.incusspawn.proxy;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Where a {@link BodySpool} spills what does not fit its share of the {@link SpoolBudget}. */
interface SpoolStore {

    /** One spilled body: written once, front to back, and read back as often as it is sent. */
    interface SpoolFile {
        Future<Void> write(Buffer data, long position);

        Future<Buffer> read(long position, int length);

        Future<Void> close();
    }

    Future<SpoolFile> open();

    /** Whether a body may be accepted at all: checked before the proxy answers {@code 100 Continue}. */
    boolean hasRoom();

    /** Reserves {@code n} bytes about to be written, or false when they may not be. */
    boolean admit(long n);

    /** A reserved write reached the disk. */
    void written(long n);

    /** A reserved write never happened, or failed. */
    void abandoned(long n);

    /** Why {@link #admit} said no, for the client. */
    String refusal();

    /** No spool directory: bodies are held in memory as far as the budget allows, and no further. */
    record Unavailable(String reason) implements SpoolStore {
        @Override
        public Future<SpoolFile> open() {
            return Future.failedFuture(reason);
        }

        @Override
        public boolean hasRoom() {
            return true;
        }

        @Override
        public boolean admit(long n) {
            return false;
        }

        @Override
        public void written(long n) {}

        @Override
        public void abandoned(long n) {}

        @Override
        public String refusal() {
            return "the proxy has no spool directory: " + reason;
        }
    }

    /**
     * Files in an owner-only directory on the host's disk (not {@code /tmp}, which is tmpfs, so RAM,
     * on Fedora), each unlinked as soon as it is open: its name is gone at once, and closing its last
     * handle, or the proxy's end however it ends, gives its storage back.
     */
    final class OnDisk implements SpoolStore {
        private final Vertx vertx;
        private final Path dir;
        private final DiskReserve reserve;

        OnDisk(Vertx vertx, Path dir) throws IOException {
            this.vertx = vertx;
            this.dir = dir;
            Files.createDirectories(dir);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            // Only a crash between a file's open and its unlink leaves one behind
            try (var leftovers = Files.list(dir)) {
                for (var file : leftovers.toList()) Files.deleteIfExists(file);
            }
            var store = Files.getFileStore(dir);
            reserve = new DiskReserve(new DiskReserve.Probe() {
                @Override
                public long usable() throws IOException {
                    return store.getUsableSpace();
                }

                @Override
                public long total() throws IOException {
                    return store.getTotalSpace();
                }
            }, task -> vertx.executeBlocking(() -> {
                task.run();
                return null;
            }, false), System::currentTimeMillis);
        }

        @Override
        public boolean hasRoom() {
            return reserve.hasRoom();
        }

        @Override
        public boolean admit(long n) {
            return reserve.admit(n);
        }

        @Override
        public void written(long n) {
            reserve.written(n);
        }

        @Override
        public void abandoned(long n) {
            reserve.abandoned(n);
        }

        @Override
        public String refusal() {
            return reserve.describe();
        }

        @Override
        public Future<SpoolFile> open() {
            var path = dir.resolve("spool-" + UUID.randomUUID()).toString();
            var options = new OpenOptions().setCreateNew(true).setRead(true).setWrite(true).setPerms("rw-------");
            return vertx.fileSystem().open(path, options)
                    .compose(file -> vertx.fileSystem().delete(path)
                            .recover(e -> {
                                ProxyLog.warn("Could not unlink spool file " + path + ": " + e.getMessage());
                                return Future.succeededFuture();
                            })
                            .map(v -> (SpoolFile) new Opened(file)));
        }

        private record Opened(AsyncFile file) implements SpoolFile {
            @Override
            public Future<Void> write(Buffer data, long position) {
                return file.write(data, position);
            }

            @Override
            public Future<Buffer> read(long position, int length) {
                return file.read(Buffer.buffer(length), 0, position, length);
            }

            @Override
            public Future<Void> close() {
                return file.close();
            }
        }
    }
}
