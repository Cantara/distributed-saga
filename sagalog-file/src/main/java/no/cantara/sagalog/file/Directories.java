package no.cantara.sagalog.file;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Makes a created, renamed or deleted file durable: its name lives in its folder, so the folder is synced.
 */
final class Directories {

    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private Directories() {
    }

    /**
     * Syncs the folder where the platform allows it. A platform that cannot open a folder for reading (Windows) has
     * nothing to sync this way, and is skipped. A failing sync of an opened folder is reported.
     *
     * @throws IOException if the folder was opened but could not be synced
     */
    static void sync(Path folder) throws IOException {
        if (WINDOWS) {
            return;
        }
        FileChannel channel;
        try {
            channel = FileChannel.open(folder, StandardOpenOption.READ);
        } catch (IOException | UnsupportedOperationException e) {
            return; // the platform or file system cannot open a folder: nothing to sync
        }
        try (channel) {
            channel.force(true);
        }
    }
}
