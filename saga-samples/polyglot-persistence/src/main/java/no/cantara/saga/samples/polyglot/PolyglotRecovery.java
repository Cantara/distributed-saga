package no.cantara.saga.samples.polyglot;

import no.cantara.concurrent.futureselector.SelectableThreadPoolExecutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.execution.SagaExecution;
import no.cantara.saga.execution.adapter.AdapterLoader;
import no.cantara.sagalog.SagaLog;
import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryType;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

/**
 * Completes the unfinished sagas of a saga log at start-up, then truncates the log.
 */
final class PolyglotRecovery {

    private PolyglotRecovery() {
    }

    /**
     * Runs every unfinished execution in the log again, in recovery mode, and waits for it. When every execution in
     * the log has finished, the log is truncated. An execution that could not be finished keeps its entries, for the
     * next start-up.
     *
     * @param sagaLog      a log the caller owns
     * @param pool         the pool to run the sagas on
     * @param sagasByName  the saga definitions, by saga name
     * @param adapters     the adapters of the sagas
     * @return the ids of the executions that were recovered and have now finished
     */
    @SuppressWarnings("deprecation") // SagaExecution takes the executor under its old name
    static List<String> recover(SagaLog sagaLog, SelectableThreadPoolExecutor pool, Map<String, Saga> sagasByName, AdapterLoader adapters) {
        Map<String, List<SagaLogEntry>> entriesByExecutionId = sagaLog.readIncompleteSagas()
                .collect(Collectors.groupingBy(SagaLogEntry::getExecutionId, LinkedHashMap::new, Collectors.toList()));
        if (entriesByExecutionId.isEmpty()) {
            return List.of();
        }
        List<String> recovered = new ArrayList<>();
        boolean allFinished = true;
        for (Map.Entry<String, List<SagaLogEntry>> execution : entriesByExecutionId.entrySet()) {
            String executionId = execution.getKey();
            List<SagaLogEntry> entries = execution.getValue();
            if (isFinished(entries)) {
                continue;
            }
            Optional<SagaLogEntry> start = entries.stream()
                    .filter(e -> e.getEntryType() == SagaLogEntryType.Start && Saga.ID_START.equals(e.getNodeId()))
                    .findFirst();
            Saga saga = start.map(e -> sagasByName.get(e.getSagaName())).orElse(null);
            if (saga == null) {
                System.err.format("Cannot recover saga execution %s in %s: unknown saga%n", executionId, sagaLog.id());
                allFinished = false;
                continue;
            }
            // the start adapter stored the input with toString(); turn it back into the input object
            String serializedInput = start.get().getJsonData();
            Object input = serializedInput == null ? null : new JSONObject(serializedInput);
            try {
                new SagaExecution(sagaLog, pool, saga, adapters)
                        .executeSaga(executionId, input, true, result -> {
                        })
                        .getCompletionFuture().join();
            } catch (CompletionException e) {
                // a rollback also fails the future; whether the saga finished is read from the log below
                System.err.format("Recovery of saga execution %s in %s failed: %s%n", executionId, sagaLog.id(), e.getCause());
            }
            if (isFinished(sagaLog.readEntries(executionId).collect(Collectors.toList()))) {
                recovered.add(executionId);
            } else {
                allFinished = false;
            }
        }
        if (allFinished) {
            sagaLog.truncate().join();
        }
        return recovered;
    }

    /**
     * @return true if the entries hold the End of the end node: the saga succeeded or was rolled back
     */
    static boolean isFinished(List<SagaLogEntry> entries) {
        for (SagaLogEntry entry : entries) {
            if (entry.getEntryType() == SagaLogEntryType.End && Saga.ID_END.equals(entry.getNodeId())) {
                return true;
            }
        }
        return false;
    }
}
