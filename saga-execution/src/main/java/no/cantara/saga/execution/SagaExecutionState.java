package no.cantara.saga.execution;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A snapshot of one saga execution: which phase it is in, which nodes are running, and which wait for others. For
 * operators and tests, to see why a saga has not completed: a join holds no thread while it waits, so a thread dump
 * does not show it.
 * <p>
 * Taken by {@link SagaHandoffControl#getState()}. The values are read one after another while the saga runs, not
 * atomically, so two of them may be a moment apart: a node can be reported as running and as visited at once. The
 * completion future completes from within the visit of the last node ({@code E}, or {@code S} after a rollback), so a
 * state taken just after it completed can still show that node running and its walk counted, for a moment.
 * Instances are immutable.
 */
public final class SagaExecutionState {

    /**
     * Where an execution is.
     */
    public enum Phase {
        /**
         * Visiting the nodes forward, from {@code S}.
         */
        FORWARD,
        /**
         * Rolling back after an abort: visiting the nodes backward, from {@code E}, compensating them.
         */
        ROLLBACK,
        /**
         * Completed: the completion future is done.
         */
        COMPLETED
    }

    private final String executionId;
    private final Phase phase;
    private final Set<String> visitedNodeIds;
    private final Set<String> runningNodeIds;
    private final Map<String, Integer> waitingNodeIds;
    private final int walks;
    private final Throwable failure;

    SagaExecutionState(String executionId, Phase phase, Set<String> visitedNodeIds, Set<String> runningNodeIds,
                       Map<String, Integer> waitingNodeIds, int walks, Throwable failure) {
        this.executionId = executionId;
        this.phase = phase;
        this.visitedNodeIds = Collections.unmodifiableSet(new TreeSet<>(visitedNodeIds));
        this.runningNodeIds = Collections.unmodifiableSet(new TreeSet<>(runningNodeIds));
        this.waitingNodeIds = Collections.unmodifiableMap(new TreeMap<>(waitingNodeIds));
        this.walks = walks;
        this.failure = failure;
    }

    /**
     * @return the id of the execution
     */
    public String getExecutionId() {
        return executionId;
    }

    /**
     * @return the phase the execution is in
     */
    public Phase getPhase() {
        return phase;
    }

    /**
     * @return the ids of the nodes whose visit has ended in the current traversal: forward, or, in a rollback, backward.
     * Not null; unmodifiable
     */
    public Set<String> getVisitedNodeIds() {
        return visitedNodeIds;
    }

    /**
     * @return the ids of the nodes being visited now: an action or a compensating action running, a saga-log write for
     * the node, or its {@code preAction} or {@code postAction}. Not null; unmodifiable
     */
    public Set<String> getRunningNodeIds() {
        return runningNodeIds;
    }

    /**
     * @return for each node that some, but not all, of the nodes it depends on have signalled: how many it still waits
     * for. A node no other node has signalled yet is not listed. Not null; unmodifiable
     */
    public Map<String, Integer> getWaitingNodeIds() {
        return waitingNodeIds;
    }

    /**
     * @return the walks of the execution that may still run: running on the pool, or submitted and queued
     */
    public int getWalks() {
        return walks;
    }

    /**
     * @return the execution's first failure, once one is recorded (an {@code AbortSagaException} is not a failure until
     * its rollback has ended); or null
     */
    public Throwable getFailure() {
        return failure;
    }

    @Override
    public String toString() {
        return "SagaExecutionState{executionId='" + executionId + "', phase=" + phase + ", running=" + runningNodeIds
                + ", waiting=" + waitingNodeIds + ", visited=" + visitedNodeIds + ", walks=" + walks
                + ", failure=" + failure + "}";
    }
}
