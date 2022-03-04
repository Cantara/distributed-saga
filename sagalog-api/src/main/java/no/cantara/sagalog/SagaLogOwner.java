package no.cantara.sagalog;

import java.util.Objects;

public class SagaLogOwner {

    private final String ownerId;

    public SagaLogOwner(String ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerId() {
        return ownerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SagaLogOwner that = (SagaLogOwner) o;
        return ownerId.equals(that.ownerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ownerId);
    }

    @Override
    public String toString() {
        return "SagaLogOwner{" +
                "ownerId='" + ownerId + '\'' +
                '}';
    }
}
