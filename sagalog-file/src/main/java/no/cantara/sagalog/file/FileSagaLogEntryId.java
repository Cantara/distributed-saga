package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogEntryId;

import java.util.Objects;

class FileSagaLogEntryId implements SagaLogEntryId {
    final long id;

    FileSagaLogEntryId(long id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FileSagaLogEntryId that = (FileSagaLogEntryId) o;
        return id == that.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "FileSagaLogEntryId{" +
                "id=" + id +
                '}';
    }
}
