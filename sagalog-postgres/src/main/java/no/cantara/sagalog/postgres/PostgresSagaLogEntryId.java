package no.cantara.sagalog.postgres;

import de.huxhorn.sulky.ulid.ULID;
import no.cantara.sagalog.SagaLogEntryId;

import java.util.Objects;

class PostgresSagaLogEntryId implements SagaLogEntryId {
    final ULID.Value id;

    PostgresSagaLogEntryId(ULID.Value id) {
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
