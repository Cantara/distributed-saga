package no.cantara.saga.execution;

import no.cantara.saga.api.SagaNode;
import no.cantara.saga.execution.adapter.SagaAdapter;

import java.util.Deque;
import java.util.Map;

/**
 * What the {@code preAction} and {@code postAction} callbacks of
 * {@link SagaExecution#executeSaga(String, Object, boolean, java.util.function.Consumer, java.util.function.Consumer, java.util.function.Consumer)}
 * get for a node. The callbacks run on the thread of the walk that visits the node.
 */
public class SagaExecutionTraversalContext {
    private final Map<SagaNode, Object> outputByNode;
    private final Deque<SagaNode> ancestors;
    private final SagaNode node;
    private final SagaAdapter<?> adapter;
    private final String output;

    public SagaExecutionTraversalContext(Map<SagaNode, Object> outputByNode, Deque<SagaNode> ancestors, SagaNode node, SagaAdapter<?> adapter, String output) {
        this.outputByNode = outputByNode;
        this.ancestors = ancestors;
        this.node = node;
        this.adapter = adapter;
        this.output = output;
    }

    /**
     * @return the outputs of the nodes this node depends on, by node
     */
    public Map<SagaNode, Object> getOutputByNode() {
        return outputByNode;
    }

    /**
     * @return the nodes on the path the walk took from the first node to this one, first node first. The deque
     * belongs to this node's walk (no other walk shares it); read it, do not change it.
     */
    public Deque<SagaNode> getAncestors() {
        return ancestors;
    }

    /**
     * @return the node
     */
    public SagaNode getNode() {
        return node;
    }

    /**
     * @return the node's adapter
     */
    public SagaAdapter<?> getAdapter() {
        return adapter;
    }

    /**
     * @return in {@code postAction}, the serialized output of the node's action (null if it had none); in
     * {@code preAction}, null
     */
    public String getOutput() {
        return output;
    }
}
