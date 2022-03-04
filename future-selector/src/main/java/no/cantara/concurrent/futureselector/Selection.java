package no.cantara.concurrent.futureselector;

public class Selection<F, C> {

    public SelectableFuture<F> future;
    public C control;

    Selection(SelectableFuture<F> future, C control) {
        this.future = future;
        this.control = control;
    }
}
