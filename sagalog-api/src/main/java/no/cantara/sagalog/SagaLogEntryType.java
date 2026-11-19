package no.cantara.sagalog;

public enum SagaLogEntryType {
    Start,
    End,
    Abort,
    Comp,     // the compensating action of the node has completed
    CompStart, // the compensating action of the node is about to run (written before it starts)
    Ignore // Entries of this type should always be ignored, may be used internally by the saga-log.
}
