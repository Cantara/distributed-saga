package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLogEntryType;

final class PostgresSagaTools {

    private PostgresSagaTools() {
    }

    static SagaLogEntryType fromShort(short value) {
        return switch (value) {
            case 1 -> SagaLogEntryType.Start;
            case 2 -> SagaLogEntryType.End;
            case 3 -> SagaLogEntryType.Abort;
            case 4 -> SagaLogEntryType.Comp;
            case 5 -> SagaLogEntryType.Ignore;
            case 6 -> SagaLogEntryType.CompStart;
            default -> throw new IllegalArgumentException("Illegal SagaLogEntryType short-value: " + value);
        };
    }

    static short toShort(SagaLogEntryType type) {
        return switch (type) {
            case Start -> 1;
            case End -> 2;
            case Abort -> 3;
            case Comp -> 4;
            case Ignore -> 5;
            case CompStart -> 6;
        };
    }
}
