package no.cantara.sagalog.postgres;

import no.cantara.sagalog.SagaLogEntryId;

import java.util.Objects;
import java.util.UUID;

class PostgresSagaLogEntryId implements SagaLogEntryId {
    final UUID id;

    PostgresSagaLogEntryId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("id cannot be null");
        }
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PostgresSagaLogEntryId that = (PostgresSagaLogEntryId) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "PostgresSagaLogEntryId{" +
                "id=" + id +
                '}';
    }
}
