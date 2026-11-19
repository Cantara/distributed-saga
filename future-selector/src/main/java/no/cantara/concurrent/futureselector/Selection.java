package no.cantara.concurrent.futureselector;

/**
 * A future selected by a {@link FutureSelector}, with the control value it was added with. Immutable.
 *
 * @param <F> the result type of the future
 * @param <C> the type of the control value
 */
public class Selection<F, C> {

    public final SelectableFuture<F> future;
    public final C control;

    Selection(SelectableFuture<F> future, C control) {
        this.future = future;
        this.control = control;
    }
}
