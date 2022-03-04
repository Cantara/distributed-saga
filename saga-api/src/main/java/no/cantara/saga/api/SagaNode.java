package no.cantara.saga.api;

import java.util.Collection;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

public class SagaNode {

    public final Collection<SagaNode> incoming = new LinkedList<>();
    public final Collection<SagaNode> outgoing = new LinkedList<>();

    public final String id;
    public final String adapter;

    SagaNode(String id, String adapter) {
        this.id = id;
        this.adapter = adapter;
    }

    void depthFirstPreOrderFullTraversal(int depth, Set<String> ancestors, Set<String> visitedNodeIds, BiConsumer<Set<String>, SagaNode> visit) {
        if (visitedNodeIds.contains(id)) {
            return;
        }
        visitedNodeIds.add(id);
        visit.accept(ancestors, this);

        ancestors.add(id);
        try {
            for (SagaNode child : outgoing) {
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
            for (SagaNode child : incoming) {
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
}
