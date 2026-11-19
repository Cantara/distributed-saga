package no.cantara.saga.samples.polyglot;

import no.cantara.saga.api.Saga;
import no.cantara.saga.samples.polyglot.adapter.PublishToPubSub;
import no.cantara.saga.samples.polyglot.adapter.WriteToGraph;
import no.cantara.saga.samples.polyglot.adapter.WriteToObjectStore;
import no.cantara.saga.samples.polyglot.adapter.WriteToRDBMS;

import java.util.Map;

/**
 * The saga of the sample: write to three persistence technologies in parallel, then publish an event.
 */
final class PolyglotSaga {

    static final String NAME = "Polyglot Saga";

    /**
     * Built once: a saga is immutable and serves every execution.
     */
    static final Saga SAGA = Saga.start(NAME)
            .linkTo("rdbms", "graph", "objectstore")
            .id("rdbms").adapter(WriteToRDBMS.NAME).linkTo("pubsub")
            .id("graph").adapter(WriteToGraph.NAME).linkTo("pubsub")
            .id("objectstore").adapter(WriteToObjectStore.NAME).linkTo("pubsub")
            .id("pubsub").adapter(PublishToPubSub.NAME).linkToEnd()
            .end();

    /**
     * The sagas this application runs, by name, so that recovery finds the definition of a logged execution.
     */
    static final Map<String, Saga> SAGAS_BY_NAME = Map.of(NAME, SAGA);

    private PolyglotSaga() {
    }
}
