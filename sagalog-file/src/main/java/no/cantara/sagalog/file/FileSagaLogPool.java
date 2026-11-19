package no.cantara.sagalog.file;


import no.cantara.sagalog.AbstractSagaLogPool;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogBusyException;
import no.cantara.sagalog.SagaLogId;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Stream;

class FileSagaLogPool extends AbstractSagaLogPool {

    private static final System.Logger LOG = System.getLogger(FileSagaLogPool.class.getName());

    private final Path folder;

    FileSagaLogPool(Path folder, String clusterInstanceId) {
        super(clusterInstanceId);
        this.folder = folder;
    }

    @Override
    public FileSagaLogId idFor(String clusterInstanceId, String logName) {
        return new FileSagaLogId(folder, clusterInstanceId, logName);
    }

    /**
     * Every {@code *.sagalog} file in the folder. A file with that suffix whose name is not a saga-log id is skipped
     * with a warning.
     *
     * @throws UncheckedIOException if the folder cannot be listed
     */
    @Override
    public Set<SagaLogId> clusterWideLogIds() {
        Set<SagaLogId> ids = new HashSet<>();
        try (Stream<Path> paths = Files.list(folder)) {
            for (Iterator<Path> it = paths.iterator(); it.hasNext(); ) {
                Path path = it.next();
                if (!path.getFileName().toString().endsWith(".sagalog")) {
                    continue;
                }
                try {
                    ids.add(new FileSagaLogId(path.toAbsolutePath()));
                } catch (IllegalArgumentException | IllegalStateException e) {
                    LOG.log(System.Logger.Level.WARNING, "Skipping a file that is not a saga-log: {0} ({1})", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot list the saga-log folder " + folder, e);
        } catch (UncheckedIOException e) {
            throw new UncheckedIOException("Cannot list the saga-log folder " + folder, e.getCause());
        }
        return ids;
    }

    @Override
    protected SagaLog connectExternal(SagaLogId logId) throws SagaLogBusyException {
        return new FileSagaLog(logId);
    }

    /**
     * Deletes the log file and its lock file, holding the log's lock while it does.
     *
     * @throws SagaLogBusyException if another process, or an open log in this process, holds the log
     * @throws UncheckedIOException if a file cannot be deleted
     */
    @Override
    protected boolean deleteExternal(SagaLogId logId) {
        Path path = ((FileSagaLogId) logId).getPath();
        try {
            boolean deleted;
            try (FileSagaLogLock lock = FileSagaLogLock.acquire(path)) {
                deleted = Files.deleteIfExists(path);
                Files.deleteIfExists(lock.file()); // while it is held: see "One open log per file" in the docs
            }
            Directories.sync(path.getParent());
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete saga-log " + path, e);
        }
    }
}
