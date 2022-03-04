package no.cantara.sagalog.memory;

import no.cantara.sagalog.SagaLogEntryId;

import java.util.Objects;

class MemorySagaLogEntryId implements SagaLogEntryId {
    final long id;

    MemorySagaLogEntryId(long id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MemorySagaLogEntryId that = (MemorySagaLogEntryId) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MemorySagaLogEntryId{" +
                "id=" + id +
                '}';
    }
}
