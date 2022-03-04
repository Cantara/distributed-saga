package no.cantara.saga.execution;

import no.cantara.concurrent.futureselector.SelectableFuture;
import no.cantara.concurrent.futureselector.SelectableThreadPoolExectutor;
import no.cantara.saga.api.Saga;
import no.cantara.saga.api.SagaNode;
import org.testng.annotations.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

public class SagaTraversalTest {

    private static final AtomicLong nextWorkerId = new AtomicLong(1);

    private static final SelectableThreadPoolExectutor executorService = new SelectableThreadPoolExectutor(
            5, 20,
            60, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            runnable -> {
                Thread thread = new Thread(runnable);
                thread.setName("test-traversal-worker-" + nextWorkerId.getAndIncrement());
                thread.setUncaughtExceptionHandler((t, e) -> {
                    System.err.println("Uncaught exception in thread " + thread.getName());
                    e.printStackTrace();
                });
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

    @Test
    public void thatEmptySagaIsTraversedInLegalOrder() {
        Saga saga = Saga
                .start("The Empty Saga").linkToEnd()
                .end();

        SagaTraversal sagaTraversal = new SagaTraversal(executorService, saga);
        sagaTraversal.forward(validateForwardTraversalOrder(saga)).waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS);
        sagaTraversal.backward(validateBackwardTraversalOrder(saga)).waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS);
    }

    @Test
    public void thatSimpleSagaIsTraversedInLegalOrder() {
        Saga saga = Saga
                .start("A Simple Saga").linkTo("1")
                .id("1").adapter("Adapter").linkToEnd()
                .end();

        SagaTraversal sagaTraversal = new SagaTraversal(executorService, saga);
        sagaTraversal.forward(validateForwardTraversalOrder(saga)).waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS);
        sagaTraversal.backward(validateBackwardTraversalOrder(saga)).waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS);
    }

    @Test
    public void thatComplexSagaIsTraversedInLegalOrder() {
        Saga saga = Saga
                .start("The complex saga").linkTo("A1", "A2")
                .id("A1").adapter("Adapter").linkToEnd()
                .id("A2").adapter("Adapter").linkTo("B1", "B2")
                .id("B1").adapter("Adapter").linkToEnd()
                .id("B2").adapter("Adapter").linkTo("C1", "C2", "C3")
                .id("C1").adapter("Adapter").linkToEnd()
                .id("C2").adapter("Adapter").linkToEnd()
                .id("C3").adapter("Adapter").linkTo("B1")
                .end();

        SagaTraversal sagaTraversal = new SagaTraversal(executorService, saga);
        sagaTraversal.forward(validateForwardTraversalOrder(saga, printVisitResults()))
                .waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS);
        sagaTraversal.backward(validateBackwardTraversalOrder(saga, printVisitResults()))
                .waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS);
    }

    @Test
    public void thatBigAndComplexSagaIsTraversedInLegalOrder() {
        Saga saga = Saga
                .start("The complex saga").linkTo("A1", "A2")
                .id("A1").adapter("Adapter").linkTo("C3", "D1", "D3", "D4")
                .id("A2").adapter("Adapter").linkTo("B1", "B2")
                .id("B1").adapter("Adapter").linkTo("E1")
                .id("B2").adapter("Adapter").linkTo("C1", "C2", "C3")
                .id("C1").adapter("Adapter").linkTo("D2")
                .id("C2").adapter("Adapter").linkTo("D2")
                .id("C3").adapter("Adapter").linkTo("B1", "D2")
                .id("D1").adapter("Adapter").linkTo("E1")
                .id("D2").adapter("Adapter").linkTo("B1", "E2")
                .id("D3").adapter("Adapter").linkTo("B1")
                .id("D4").adapter("Adapter").linkTo("E2")
                .id("E1").adapter("Adapter").linkToEnd()
                .id("E2").adapter("Adapter").linkToEnd()
                .end();

        SagaTraversal sagaTraversal = new SagaTraversal(executorService, saga);
        //System.out.println("FORWARD:");
        sagaTraversal.forward(validateForwardTraversalOrder(saga, printVisitResults()))
                .waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS, printWalks(), (node, output) -> {
                });
        //System.out.println();
        //System.out.println("BACKWARD:");
        sagaTraversal.backward(validateBackwardTraversalOrder(saga, printVisitResults()))
                .waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(10, TimeUnit.SECONDS, printWalks(), (node, output) -> {
                });
        //System.out.println();
    }

    private Consumer<SelectableFuture<List<String>>> printWalks() {
        return selectableFuture -> {
            try {
                List<String> walkedNodeIds = selectableFuture.get();
                //System.out.printf("WALK: %s\n", walkedNodeIds);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private Consumer<String> printVisitResults() {
        return value -> {
            //System.out.printf("VISIT: %s  :: %s\n", String.valueOf(value), Thread.currentThread().getName());
        };
    }

    private Function<SagaTraversalElement, Object> validateForwardTraversalOrder(Saga saga) {
        return validateTraversalOrder(saga, true, str -> {
        });
    }

    private Function<SagaTraversalElement, Object> validateBackwardTraversalOrder(Saga saga) {
        return validateTraversalOrder(saga, false, str -> {
        });
    }

    private Function<SagaTraversalElement, Object> validateForwardTraversalOrder(Saga saga, Consumer<String> visitAction) {
        return validateTraversalOrder(saga, true, visitAction);
    }

    private Function<SagaTraversalElement, Object> validateBackwardTraversalOrder(Saga saga, Consumer<String> visitAction) {
        return validateTraversalOrder(saga, false, visitAction);
    }

    private Function<SagaTraversalElement, Object> validateTraversalOrder(Saga saga, boolean forward, Consumer<String> visitAction) {
        final Set<String> visited = new ConcurrentSkipListSet<>();
        final Map<String, Set<String>> dependencyMap = forward ? sagaForwardDependencyMap(saga) : sagaBackwardDependencyMap(saga);
        return ste -> {
            Set<String> dependsOn = dependencyMap.get(ste.node.id);
            for (String dependentId : dependsOn) {
                if (!visited.contains(dependentId)) {
                    throw new IllegalStateException("Traversal error, node(" + ste.node.id + ") was visited before node(" + dependentId + ")");
                }
            }
            if (visited.contains(ste.node.id)) {
                throw new IllegalStateException("Traversal error, node(" + ste.node.id + ") was visited more than once");
            }
            visited.add(ste.node.id);
            String result = "(\"" + ste.node.id + "\")";
            visitAction.accept(result);
            return result;
        };
    }

    private Map<String, Set<String>> sagaForwardDependencyMap(Saga saga) {
        Map<String, Set<String>> incomingById = new ConcurrentHashMap<>();
        sagaDependencyMap(saga.getEndNode(), incomingById, true);
        return incomingById;
    }

    private Map<String, Set<String>> sagaBackwardDependencyMap(Saga saga) {
        Map<String, Set<String>> outgoingById = new ConcurrentHashMap<>();
        sagaDependencyMap(saga.getStartNode(), outgoingById, false);
        return outgoingById;
    }

    private Set<String> sagaDependencyMap(SagaNode node, Map<String, Set<String>> dependentById, boolean forward) {
        Sets.Builder<String> sb = Sets.set();
        for (SagaNode sn : (forward ? node.incoming : node.outgoing)) {
            sb.add(sagaDependencyMap(sn, dependentById, forward));
            sb.add(sn.id);
        }
        LinkedHashSet<String> set = sb.linkedHashSet();
        dependentById.put(node.id, set);
        return set;
    }

}
