package no.cantara.saga.execution;

import no.cantara.saga.api.SagaNode;

import java.util.Deque;
import java.util.Map;

class SagaTraversalElement {
    final Map<SagaNode, Object> outputByNode;
    final Deque<SagaNode> ancestors;
    final SagaNode node;

    SagaTraversalElement(Map<SagaNode, Object> outputByNode, Deque<SagaNode> ancestors, SagaNode node) {
        this.outputByNode = outputByNode;
        this.ancestors = ancestors;
        this.node = node;
    }
}
