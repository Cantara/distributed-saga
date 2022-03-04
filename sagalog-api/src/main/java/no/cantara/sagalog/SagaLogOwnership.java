package no.cantara.sagalog;

import java.time.ZonedDateTime;
import java.util.Objects;

public class SagaLogOwnership {

    private final SagaLogOwner owner;
    private final SagaLogId logId;
    private final ZonedDateTime acquired;

    public SagaLogOwnership(SagaLogOwner owner, SagaLogId logId, ZonedDateTime acquired) {
        this.owner = owner;
        this.logId = logId;
        this.acquired = acquired;
    }

    public SagaLogOwner getOwner() {
        return owner;
    }

    public SagaLogId getLogId() {
        return logId;
    }

    public ZonedDateTime getAcquired() {
        return acquired;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaLogOwnership that = (SagaLogOwnership) o;
        return owner.equals(that.owner) &&
                logId.equals(that.logId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, logId);
    }

    @Override
    public String toString() {
        return "SagaLogOwnership{" +
                "owner=" + owner +
                ", logId='" + logId + '\'' +
                ", acquired=" + acquired +
                '}';
    }
}
