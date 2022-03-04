package no.cantara.saga.api;

import org.testng.annotations.Test;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.testng.Assert.assertEquals;

public class SagaTest {

    @Test(expectedExceptions = SagaException.class)
    public void thatLinkToSelfIsInvalid() {
        Saga
                .start("Link to self saga").linkTo("s1")
                .id("s1").adapter("A").linkTo("s1", "s2")
                .id("s2").adapter("B").linkToEnd()
                .end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatLinkToParentIsInvalid() {
        Saga
                .start("Link to self saga").linkTo("s1")
                .id("s1").adapter("A").linkTo("s2")
                .id("s2").adapter("B").linkTo("s1", "s3")
                .id("s3").adapter("C").linkToEnd()
                .end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatCyclicComplexSagaIsInvalid() {
        Saga
                .start("Cyclic Complex saga").linkTo("c1", "c2", "c3")
                .id("c1").adapter("something").linkToEnd()
                .id("c2").adapter("anything").linkTo("c7")
                .id("c3").adapter("that").linkTo("c4")
                .id("c4").adapter("complex-branch").linkTo("c5", "c6")
                .id("c5").adapter("sub_route_A").linkTo("c7")
                .id("c6").adapter("sub_route_B").linkTo("c7", "c3")
                .id("c7").adapter("Aggregate_A,_B_and_anything").linkToEnd()
                .end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatMissingNodesSagaIsInvalid() {
        Saga
                .start("Missing nodes saga").linkTo("r1", "m2")
                .id("r1").adapter("").linkToEnd()
                .end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatUnreachableNodesSagaIsInvalid() {
        Saga
                .start("Unreachable nodes saga").linkToEnd()
                .id("u1").adapter("").linkToEnd()
                .id("u2").adapter("").linkToEnd()
                .end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatDuplicatedNodeSagaIsInvalid() {
        Saga
                .start("Duplicated node id saga").linkTo("d1")
                .id("d1").adapter("").linkToEnd()
                .id("d1").adapter("").linkToEnd()
                .end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatDuplicatedEndNodeSagaIsInvalid() {
        Saga
                .start("Duplicated node id saga").linkToEnd()
                .id(Saga.ID_END).adapter("").linkToEnd()
                .end();
    }

    @Test
    public void thatReverseTraversalVisitsAllTheSameNodesThatForwardTraversal() {
        Saga traversalSaga = Saga
                .start("Reverse traversal saga").linkTo("c1", "c2", "c3")
                .id("c1").adapter("something").linkToEnd()
                .id("c2").adapter("anything").linkTo("c7")
                .id("c3").adapter("that").linkTo("c4")
                .id("c4").adapter("complex-branch").linkTo("c5", "c6")
                .id("c5").adapter("sub_route_A").linkTo("c7")
                .id("c6").adapter("sub_route_B").linkTo("c7")
                .id("c7").adapter("Aggregate_A,_B_and_anything").linkToEnd()
                .end();
        Set<String> forwardVisited = new LinkedHashSet<>();
        Set<String> reverseVisited = new LinkedHashSet<>();
        traversalSaga.depthFirstPreOrderFullTraversal((ancestors, node) -> forwardVisited.add(node.id));
        traversalSaga.reverseDepthFirstPreOrderFullTraversal((ancestors, node) -> reverseVisited.add(node.id));
        assertEquals(reverseVisited, forwardVisited);
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatNoLinkToSagaIsInvalid() {
        Saga.start("No linkTo saga").linkTo().end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatNullLinkToSagaIsInvalid() {
        Saga.start("Null linkTo saga").linkTo((String) null).end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatNullListLinkToSagaIsInvalid() {
        Saga.start("Null list linkTo saga").linkTo((String[]) null).end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatNullNullLinkToSagaIsInvalid() {
        Saga.start("Null Null linkTo saga").linkTo(null, null).end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatEmptyLinkToSagaIsInvalid() {
        Saga.start("Empty linkTo saga").linkTo("").end();
    }

    @Test(expectedExceptions = SagaException.class)
    public void thatOneOfTwoEmptyLinkToSagaIsInvalid() {
        Saga.start("One of two empty linkTo saga").linkTo("1", "").end();
    }
}
