package no.cantara.saga.execution;

import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.SagaAdapter;

import java.util.Deque;
import java.util.Map;

public class SagaExecutionTraversalContext {
    private final Map<SagaNode, Object> outputByNode;
    private final Deque<SagaNode> ancestors;
    private final SagaNode node;
    private final SagaAdapter adapter;
    private final String output;

    public SagaExecutionTraversalContext(Map<SagaNode, Object> outputByNode, Deque<SagaNode> ancestors, SagaNode node, SagaAdapter adapter, String output) {
        this.outputByNode = outputByNode;
        this.ancestors = ancestors;
        this.node = node;
        this.adapter = adapter;
        this.output = output;
    }

    public Map<SagaNode, Object> getOutputByNode() {
        return outputByNode;
    }

    public Deque<SagaNode> getAncestors() {
        return ancestors;
    }

    public SagaNode getNode() {
        return node;
    }

    public SagaAdapter getAdapter() {
        return adapter;
    }

    public String getOutput() {
        return output;
    }
}
