package no.cantara.concurrent.futureselector;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class FutureSelectorTest {

    static class Result {
        final String value;

        Result(String value) {
            this.value = value;
        }
    }

    static final Random random = new Random(System.currentTimeMillis());

    static class RandomTask implements Callable<Result> {

        @Override
        public Result call() throws Exception {
            int randomWaitTime = random.nextInt(50);
            Thread.sleep(randomWaitTime);
            return new Result("{ \"rnd\" : \"" + randomWaitTime + "\" }");
        }
    }

    @Test
    public void thatSelectorWorks() throws ExecutionException, InterruptedException {
        /*
         * Set up worker thread-pool
         */
        AtomicInteger nextWorkerId = new AtomicInteger(1);
        SelectableThreadPoolExectutor threadPool = new SelectableThreadPoolExectutor(
                10, 10,
                0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable);
                    thread.setName("unit-test-pool-worker" + nextWorkerId.getAndIncrement());
                    thread.setUncaughtExceptionHandler((t, e) -> {
                        System.err.println("Uncaught exception in thread " + thread.getName());
                        e.printStackTrace();
                    });
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );

        // Start many tasks asynchonously and add them to selector
        FutureSelector<Result, String> selector = new FutureSelector<>();
        int N = 100;
        for (int i = 0; i < N; i++) {
            SelectableFuture<Result> future = threadPool.submit(new RandomTask());
            selector.add(future, "control-" + i);
        }

        // Select one-by-one in completion order until they all have completed.
        int n = 0;
        while (selector.pending()) {
            Selection<Result, String> selection = selector.select();
            Result result = selection.future.get(); // will never block
            n++;
            // System.out.format("Selection: control: \"%s\", result: %s\n", selection.control, result.value);
        }

        threadPool.shutdown();
        threadPool.awaitTermination(10, TimeUnit.SECONDS);

        Assert.assertEquals(n, N);
    }
}
