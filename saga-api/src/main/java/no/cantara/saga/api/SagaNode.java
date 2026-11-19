package no.cantara.saga.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A node of a {@link Saga}: its id, the name of its adapter, and its links to the nodes before it ({@link #incoming})
 * and after it ({@link #outgoing}), in the order they were declared.
 * <p>
 * The link collections are unmodifiable: a write throws {@link UnsupportedOperationException}. Only the builder sets
 * the links, before {@link Saga.SagaBuilder#end()} returns the saga, so the links of a built saga never change.
 */
public class SagaNode {

    private final List<SagaNode> incomingLinks = new ArrayList<>(2);
    private final List<SagaNode> outgoingLinks = new ArrayList<>(2);

    /**
     * The nodes linking to this node, in declaration order. Unmodifiable.
     */
    public final Collection<SagaNode> incoming = Collections.unmodifiableList(incomingLinks);
    /**
     * The nodes this node links to, in declaration order. Unmodifiable.
     */
    public final Collection<SagaNode> outgoing = Collections.unmodifiableList(outgoingLinks);

    public final String id;
    public final String adapter;

    SagaNode(String id, String adapter) {
        this.id = id;
        this.adapter = adapter;
    }

    /**
     * Used by the builder only, before the saga is built.
     */
    void addIncoming(SagaNode node) {
        incomingLinks.add(node);
    }

    /**
     * Used by the builder only, before the saga is built.
     */
    void addOutgoing(SagaNode node) {
        outgoingLinks.add(node);
    }

    void depthFirstPreOrderFullTraversal(int depth, Set<String> ancestors, Set<String> visitedNodeIds, BiConsumer<Set<String>, SagaNode> visit) {
        if (visitedNodeIds.contains(id)) {
            return;
        }
        visitedNodeIds.add(id);
        visit.accept(ancestors, this);

        ancestors.add(id);
        try {
            for (SagaNode child : outgoingLinks) {
                child.depthFirstPreOrderFullTraversal(depth + 1, ancestors, visitedNodeIds, visit);
            }
        } finally {
            ancestors.remove(id);
        }
    }

    void reverseDepthFirstPreOrderFullTraversal(int depth, Set<String> ancestors, Set<String> visitedNodeIds, BiConsumer<Set<String>, SagaNode> visit) {
        if (visitedNodeIds.contains(id)) {
            return;
        }
        visitedNodeIds.add(id);
        visit.accept(ancestors, this);

        ancestors.add(id);
        try {
            for (SagaNode child : incomingLinks) {
                child.reverseDepthFirstPreOrderFullTraversal(depth + 1, ancestors, visitedNodeIds, visit);
            }
        } finally {
            ancestors.remove(id);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaNode sagaNode = (SagaNode) o;
        return Objects.equals(id, sagaNode.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SagaNode{id='" + id + "', adapter='" + adapter + "'}";
    }
}
