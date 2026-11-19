package no.cantara.saga.api;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

/**
 * Immutability of a built saga, the naming rules, null arguments, duplicate links and toString (design review sa-1,
 * sa-2, sa-4, sa-5, sa-6, sa-7).
 */
public class SagaContractTest {

    private static Saga diamond() {
        return Saga.start("diamond").linkTo("a")
                .id("a").adapter("A").linkTo("b", "c")
                .id("b").adapter("B").linkTo("d")
                .id("c").adapter("C").linkTo("d")
                .id("d").adapter("D").linkToEnd()
                .end();
    }

    @Test
    public void thatTheLinksOfABuiltSagaCannotBeChanged() {
        Saga saga = diamond();
        SagaNode a = saga.getStartNode().outgoing.iterator().next();
        SagaNode end = saga.getEndNode();
        expectThrows(UnsupportedOperationException.class, () -> a.outgoing.add(end));
        expectThrows(UnsupportedOperationException.class, () -> a.incoming.clear());
        expectThrows(UnsupportedOperationException.class, () -> a.outgoing.iterator().remove());
        expectThrows(UnsupportedOperationException.class, () -> end.incoming.remove(a));
    }

    @Test
    public void thatTheLinksOfABuiltSagaAreKeptInDeclarationOrder() {
        Saga saga = diamond();
        SagaNode a = saga.getStartNode().outgoing.iterator().next();
        assertEquals(ids(a.outgoing), List.of("b", "c"));
        assertEquals(ids(a.incoming), List.of("S"));
        SagaNode d = saga.nodeById.get("d");
        assertEquals(ids(d.incoming), List.of("b", "c"));
        assertEquals(ids(d.outgoing), List.of("E"));
    }

    @Test
    public void thatASagaNameMayContainAnyCharacter() {
        String name = "name with { and }\nand \"quotes\", \\ and æøå ☺";
        Saga saga = Saga.start(name).linkToEnd().end();
        assertEquals(saga.name, name);
    }

    @Test
    public void thatAnEmptySagaNameIsAccepted() {
        assertEquals(Saga.start("").linkToEnd().end().name, "");
    }

    @Test
    public void thatAnIdMustHaveOnlyVisibleCharacters() {
        expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a b").id("a b"));
        expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a").id("a\n"));
        expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a").id("a\t"));
        // visible punctuation, including the characters JSON escapes, is allowed
        Saga saga = Saga.start("s").linkTo("{\"a\\}")
                .id("{\"a\\}").adapter("A").linkToEnd()
                .end();
        assertEquals(saga.getStartNode().outgoing.iterator().next().id, "{\"a\\}");
    }

    @Test
    public void thatAnAdapterMustHaveOnlyVisibleCharacters() {
        expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a").id("a").adapter("an adapter"));
        expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a").id("a").adapter("a\n"));
    }

    @Test
    public void thatANullSagaNameIsRejected() {
        SagaException e = expectThrows(SagaException.class, () -> Saga.start(null));
        assertTrue(e.getMessage().contains("sagaName"), e.getMessage());
    }

    @Test
    public void thatANullIdIsRejected() {
        SagaException e = expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a").id(null));
        assertTrue(e.getMessage().contains("id"), e.getMessage());
    }

    @Test
    public void thatANullAdapterIsRejected() {
        SagaException e = expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a").id("a").adapter(null));
        assertTrue(e.getMessage().contains("adapter"), e.getMessage());
    }

    @Test
    public void thatADuplicateLinkIsRejected() {
        SagaException e = expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a", "a"));
        assertTrue(e.getMessage().contains("Duplicate link"), e.getMessage());
        expectThrows(SagaException.class, () -> Saga.start("s").linkTo("a")
                .id("a").adapter("A").linkTo("E", "E"));
    }

    @Test
    public void thatSagaExceptionKeepsItsCause() {
        IllegalStateException cause = new IllegalStateException("cause");
        SagaException e = new SagaException("message", cause);
        assertEquals(e.getMessage(), "message");
        assertTrue(e.getCause() == cause);
    }

    @Test
    public void thatSagaAndNodeHaveAReadableToString() {
        Saga saga = diamond();
        assertTrue(saga.toString().contains("diamond"), saga.toString());
        assertTrue(saga.toString().contains("6"), saga.toString());
        SagaNode a = saga.nodeById.get("a");
        assertTrue(a.toString().contains("a") && a.toString().contains("A"), a.toString());
    }

    private static List<String> ids(Iterable<SagaNode> nodes) {
        List<String> ids = new ArrayList<>();
        for (SagaNode node : nodes) {
            ids.add(node.id);
        }
        return ids;
    }
}
