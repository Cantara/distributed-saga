# future-selector

`no.cantara.concurrent.futureselector:future-selector` · Java module `no.cantara.concurrent.futureselector` ·
no dependencies

A small extension of `java.util.concurrent` that adds *select* semantics to futures: wait for whichever of several
futures completes first, as `select` does for sockets. `saga-execution` uses it to wait at join nodes. Applications use
it to create the thread pool that `SagaExecution` needs.

Note the group id: it differs from the other modules (`no.cantara.saga`).

## Types

| Type | Purpose |
|---|---|
| `SelectableThreadPoolExecutor` | A `ThreadPoolExecutor` whose `submit` methods return `SelectableFuture`s. Same constructors as `ThreadPoolExecutor`. |
| `SelectableThreadPoolExectutor` | Deprecated: the same executor under its old, misspelled name, a subclass of `SelectableThreadPoolExecutor`. `SagaExecution` takes the new type, so a pool created under either name works. |
| `SelectableFuture<V>` | A `CompletableFuture<V>` that is also a `RunnableFuture<V>`, created by the executor around the submitted task. Runs its task at most once. |
| `FutureSelector<F, C>` | Collects selectable futures, each with a *control* value of type `C`, and returns them in completion order. |
| `Selection<F, C>` | One selected future and its control value (`future`, `control`); immutable (both fields are `final`). |
| `InterruptedRuntimeException`, `ExecutionRuntimeException`, `TimeoutRuntimeException` | Unchecked versions of the JDK's checked exceptions. |
| `Utils.launder(Throwable)` | Turns a checked exception into one of the unchecked ones above. For an `InterruptedException` it restores the thread's interrupt status. `Utils` holds static methods only and cannot be instantiated. |

## Using the selector

```java
SelectableThreadPoolExecutor pool = new SelectableThreadPoolExecutor(
        4, 4, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

FutureSelector<String, String> selector = new FutureSelector<>();
selector.add(pool.submit(() -> slowLookup("a")), "a");
selector.add(pool.submit(() -> slowLookup("b")), "b");

while (selector.pending()) {
    Selection<String, String> first = selector.select();   // blocks until one is done
    System.out.println(first.control + " -> " + first.future.join());
}
```

- `add(future, control)` registers a future. A future that is already done is selectable at once.
- `select()` blocks until a registered future is done and returns it. `select(timeout, unit)` returns null on timeout.
- `pending()` and `moreThanOnePending()` tell whether registered futures remain unselected.

### Threading

- Any thread may `add` a future, at any time.
- Only **one** thread selects. Two threads that select at the same time can both wait for the last pending future;
  one gets it, and the other waits for ever. `saga-execution` uses one selector per walk, on one thread.
- `pending()` and `moreThanOnePending()` are exact only on the selecting thread; on other threads they are a snapshot.

## Behaviour to know

- A future is selectable when it completes, normally or exceptionally. Check the selected future
  (`isCompletedExceptionally()`, or `get()`, which throws for a failed one). Before 2.1.0 a future that failed was never
  selected, and `select()` waited for ever for it.
- `select(timeout, unit)` returns null on timeout and selects nothing: `pending()` still counts the future.
- Cancelling a `SelectableFuture` does not interrupt its running task.
- A `SelectableFuture` runs its task at most once, like `FutureTask`: a second `run()`, from any thread, does nothing.
  Up to 2.1.0 a second `run()` ran the task again and dropped its result.
- A `select` that is interrupted throws `InterruptedRuntimeException`, and the thread stays interrupted, so a pool
  worker or a loop further up still sees the interrupt. `Utils.launder` restores the interrupt status for every
  laundered `InterruptedException`. Up to 2.1.0 the status was cleared.

These limits affect `saga-execution`; see [Known limitations](../limitations.md).
