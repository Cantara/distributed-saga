package no.cantara.saga.api;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * A saga definition: a named directed acyclic graph of {@link SagaNode}s from the start node {@value #ID_START} to the
 * end node {@value #ID_END}, built and validated by {@link #start(String)} ... {@link SagaBuilder#end()}.
 * <p>
 * Naming rules, checked by the builder:
 * <ul>
 * <li>a saga name may be any string, but not {@code null};</li>
 * <li>node ids and adapter names are identifiers: they may contain only visible characters ({@code \p{Graph}}: ASCII
 * letters, digits and punctuation), no whitespace or control characters, and not {@code null}.</li>
 * </ul>
 * A built saga is immutable: its name, its nodes and their links never change. It is safe to share between threads,
 * and one instance can serve every concurrent execution of the saga.
 */
public class Saga {

    public final static String ID_START = "S";
    public final static String ID_END = "E";

    public final static String ADAPTER_START = "SagaStart";
    public final static String ADAPTER_END = "SagaEnd";

    /**
     * Start building a new Saga with a given name. Will create the initial start-node
     * and expects caller to immediately provide link-to targets for the start-node.
     *
     * @param sagaName the name of the saga to create a builder for; any string.
     * @return a builder for the saga.
     * @throws SagaException if sagaName is null.
     */
    public static SagaBuilder.OutgoingBuilder start(String sagaName) throws SagaException {
        return new SagaBuilder(sagaName)
                .id(ID_START)
                .adapter(ADAPTER_START);
    }

    public final String name;
    final Map<String, SagaNode> nodeById;

    private Saga(String name, Map<String, SagaNode> nodeById) {
        this.name = name;
        this.nodeById = nodeById;
    }

    public SagaNode getStartNode() {
        return nodeById.get(Saga.ID_START);
    }

    public SagaNode getEndNode() {
        return nodeById.get(Saga.ID_END);
    }

    public Collection<SagaNode> nodes() {
        return new LinkedList<>(nodeById.values());
    }

    public void depthFirstPreOrderFullTraversal(BiConsumer<Set<String>, SagaNode> visit) {
        getStartNode().depthFirstPreOrderFullTraversal(0, new LinkedHashSet<>(), new LinkedHashSet<>(), visit);
    }

    public void reverseDepthFirstPreOrderFullTraversal(BiConsumer<Set<String>, SagaNode> visit) {
        getEndNode().reverseDepthFirstPreOrderFullTraversal(0, new LinkedHashSet<>(), new LinkedHashSet<>(), visit);
    }

    @Override
    public String toString() {
        return "Saga{name='" + name + "', nodes=" + nodeById.size() + "}";
    }

    public static class SagaBuilder {
        // the rule for identifiers (node ids and adapter names): visible characters only, no whitespace or control
        // characters
        private final static Pattern legalIdPattern = Pattern.compile("\\p{Graph}*");
        private final static Pattern legalAdapterPattern = Pattern.compile("\\p{Graph}*");

        final String sagaName;
        final HashMap<String, NodeState> nodeStateById = new LinkedHashMap<>();

        private SagaBuilder(String sagaName) {
            if (sagaName == null) {
                throw new SagaException("sagaName cannot be null");
            }
            this.sagaName = sagaName;
        }

        /**
         * Create a new node with the given id.
         *
         * @param id the node id: an identifier of visible characters only, with no whitespace or control characters.
         * @return a builder that expects the action to be declared.
         * @throws SagaException if the id is null, has characters that are not allowed, or is already used by another
         *                       node in this saga.
         */
        public AdapterBuilder id(String id) throws SagaException {
            if (id == null) {
                throw new SagaException("id cannot be null");
            }
            if (!legalIdPattern.matcher(id).matches()) {
                throw new SagaException("id must only contain visible characters. Whitespace- or control-characters are not allowed. id: " + id);
            }
            NodeState nodeState = new NodeState(id);
            if (nodeStateById.containsKey(id)) {
                throw new SagaException("Duplicate id: " + id);
            }
            nodeStateById.put(id, nodeState);
            return new AdapterBuilder(nodeState);
        }

        /**
         * End the saga. This will create the end-saga node and then build
         * and validate the entire saga graph. A violation throws a
         * {@link SagaException}.
         *
         * @return the Saga representation as an easy-to-use facade over a
         * directed-acyclic-graph. It is immutable and safe to share between
         * threads. The saga-serialization module turns it into JSON and back
         * ({@code SagaSerializer.toJson} and {@code SagaDeserializer.fromJson}).
         * @throws SagaException if graph cycles are detected.
         *                       Or if links point to nodes that
         *                       were never created. Or
         *                       if a created node is not
         *                       reachable from start.
         */
        public Saga end() throws SagaException {
            id(ID_END).adapter(ADAPTER_END);
            buildIncomingLinks();
            Map<String, SagaNode> sagaNodeById = buildSagaNodeGraph();
            Saga saga = new Saga(sagaName, sagaNodeById);
            validateAcyclicGraph(saga);
            validateNodeReachability(sagaNodeById, saga);
            return saga;
        }

        private void buildIncomingLinks() throws SagaException {
            for (NodeState nodeState : nodeStateById.values()) {
                for (String outgoingId : nodeState.outgoing) {
                    NodeState outgoingTarget = nodeStateById.get(outgoingId);
                    if (outgoingTarget == null) {
                        throw new SagaException("Missing node(" + outgoingId + "), linked-to by node(" + nodeState.id + ").");
                    }
                    outgoingTarget.incoming(nodeState.id);
                }
            }
        }

        private HashMap<String, SagaNode> buildSagaNodeGraph() {

            HashMap<String, SagaNode> sagaNodeById = new LinkedHashMap<>();

            for (NodeState nodeState : nodeStateById.values()) {

                /*
                 * Creating an incomplete sagaNode unless one already exists from earlier link-target node creation.
                 */
                SagaNode sagaNode = sagaNodeById.get(nodeState.id);
                if (sagaNode == null) {
                    sagaNode = new SagaNode(nodeState.id, nodeState.adapter);
                    sagaNodeById.put(nodeState.id, sagaNode);
                }

                /*
                 * Link all outgoing nodes, creating "incomplete" link-target nodes as needed.
                 */
                for (String outgoingId : nodeState.outgoing) {
                    SagaNode outgoingNode = sagaNodeById.get(outgoingId);
                    if (outgoingNode == null) {
                        // create incomplete node, will be completed later by NodeState loop
                        NodeState outgoingNodeState = nodeStateById.get(outgoingId);
                        outgoingNode = new SagaNode(outgoingNodeState.id, outgoingNodeState.adapter);
                        sagaNodeById.put(outgoingId, outgoingNode);
                    }
                    sagaNode.addOutgoing(outgoingNode);
                }

                /*
                 * Link all incoming nodes, creating "incomplete" link-target nodes as needed.
                 */
                for (String incomingId : nodeState.incoming) {
                    SagaNode incomingNode = sagaNodeById.get(incomingId);
                    if (incomingNode == null) {
                        NodeState incomingNodeState = nodeStateById.get(incomingId);
                        // create incomplete node, will be completed later by NodeState loop
                        incomingNode = new SagaNode(incomingNodeState.id, incomingNodeState.adapter);
                        sagaNodeById.put(incomingId, incomingNode);
                    }
                    sagaNode.addIncoming(incomingNode);
                }

                // sagaNode is complete
            }

            return sagaNodeById;
        }

        private void validateAcyclicGraph(Saga saga) throws SagaException {
            saga.depthFirstPreOrderFullTraversal((ancestors, node) -> {
                for (SagaNode linkTo : node.outgoing) {
                    if (node.id.equals(linkTo.id)) {
                        throw new SagaException("Saga must be a Directed Acyclic Graph (DAG). Nodes can't link to themselves: node(" + node.id + ").");
                    }
                    if (ancestors.contains(linkTo.id)) {
                        throw new SagaException("Saga must be a Directed Acyclic Graph (DAG). Detected circle where node(" + node.id + ") links to ancestor node(" + linkTo.id + ")");
                    }
                }
            });
        }

        private void validateNodeReachability(Map<String, SagaNode> sagaNodeById, Saga saga) throws SagaException {
            LinkedHashMap<String, SagaNode> unreachedNodes = new LinkedHashMap<>(sagaNodeById);
            saga.depthFirstPreOrderFullTraversal((ancestors, node) -> {
                unreachedNodes.remove(node.id);
            });
            if (unreachedNodes.size() > 0) {
                throw new SagaException("Unreachables nodes: " + unreachedNodes.keySet().stream().collect(Collectors.joining(", ")));
            }
        }

        public class AdapterBuilder {
            private final NodeState nodeState;

            private AdapterBuilder(NodeState nodeState) {
                this.nodeState = nodeState;
            }

            /**
             * Declare the adapter to use when executing action or compensating action of this node.
             *
             * @param adapter the name of the adapter to use: an identifier of visible characters only, with no
             *                whitespace or control characters.
             * @return a builder that expects the outgoing links to be declared.
             * @throws SagaException if the adapter is null or has characters that are not allowed.
             */
            public OutgoingBuilder adapter(String adapter) throws SagaException {
                if (adapter == null) {
                    throw new SagaException("adapter cannot be null");
                }
                if (!legalAdapterPattern.matcher(adapter).matches()) {
                    throw new SagaException("adapter must only contain visible characters. Whitespace- or control-characters are not allowed. adapter: " + adapter);
                }
                nodeState.adapter(adapter);
                return new OutgoingBuilder(nodeState);
            }
        }

        public class OutgoingBuilder {
            private final NodeState nodeState;

            private OutgoingBuilder(NodeState nodeState) {
                this.nodeState = nodeState;
            }

            /**
             * Link this node to the node with id given.
             *
             * @param linkTo the id of the node to link this node to. The linked to node
             *               will only be executed after the action previously specified
             *               in this node has been executed.
             * @return the saga builder ready to create more nodes or to end the saga.
             * @throws SagaException if linkTo is null or empty.
             */
            public SagaBuilder linkTo(String linkTo) throws SagaException {
                if (linkTo == null) {
                    throw new SagaException("linkTo cannot be null");
                }
                if (linkTo.trim().isEmpty()) {
                    throw new SagaException("linkTo cannot be empty");
                }
                nodeState.outgoing(linkTo);
                return SagaBuilder.this;
            }

            /**
             * Link this node to all the given linkTo nodes.
             *
             * @param linkTo ids of nodes that this node should be linked to. All the
             *               nodes linked to may be executed in parallel only after the
             *               action previously specificed in this node has been executed.
             * @return the saga builder ready to create more nodes or to end the saga.
             * @throws SagaException if linkTo is null, if any of the links are null or empty, or if this node
             *                       already links to one of them.
             */
            public SagaBuilder linkTo(String... linkTo) throws SagaException {
                if (linkTo == null) {
                    throw new SagaException("linkTo cannot be null");
                }
                for (String id : linkTo) {
                    if (id == null) {
                        throw new SagaException("linkTo argument cannot be null");
                    }
                    if (id.trim().isEmpty()) {
                        throw new SagaException("linkTo argument cannot be empty");
                    }
                    nodeState.outgoing(id);
                }
                return SagaBuilder.this;
            }

            /**
             * Link this node to the end node.
             *
             * @return the saga builder ready to create more nodes or to end the saga.
             */
            public SagaBuilder linkToEnd() {
                return linkTo(ID_END);
            }
        }
    }

    private static class NodeState {
        private final String id;
        private final Collection<String> incoming = new LinkedList<>();
        private final Collection<String> outgoing = new LinkedList<>();
        private String adapter;

        private NodeState(String id) {
            this.id = id;
        }

        private NodeState incoming(String id) {
            incoming.add(id);
            return this;
        }

        private NodeState outgoing(String id) throws SagaException {
            if (outgoing.contains(id)) {
                throw new SagaException("Duplicate link from node(" + this.id + ") to node(" + id + ")");
            }
            outgoing.add(id);
            return this;
        }

        private NodeState adapter(String action) {
            this.adapter = action;
            return this;
        }
    }
}
