package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLogEntryType;

public class PostgresSagaTools {

    static SagaLogEntryType fromShort(short value) {
        switch (value) {
            case 1:
                return SagaLogEntryType.Start;
            case 2:
                return SagaLogEntryType.End;
            case 3:
                return SagaLogEntryType.Abort;
            case 4:
                return SagaLogEntryType.Comp;
            case 5:
                return SagaLogEntryType.Ignore;
            default:
                throw new IllegalArgumentException("Illegal SagaLogEntryType short-value: " + value);
        }
    }

    static short toShort(SagaLogEntryType type) {
        switch (type) {
            case Start:
                return 1;
            case End:
                return 2;
            case Abort:
                return 3;
            case Comp:
                return 4;
            case Ignore:
                return 5;
            default:
                throw new IllegalStateException("Unsupported enum value: " + type);
        }
    }
}
