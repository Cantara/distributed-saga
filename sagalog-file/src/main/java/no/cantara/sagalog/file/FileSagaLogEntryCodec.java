package no.cantara.sagalog.file;

import no.cantara.sagalog.SagaLogEntry;
import no.cantara.sagalog.SagaLogEntryBuilder;
import no.cantara.sagalog.SagaLogEntryType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * An entry as one queue element: the format version, the id, then executionId, entry type, nodeId, sagaName and
 * data, each as a length-prefixed UTF-8 string (length -1 for null). Any text is stored as it is.
 */
final class FileSagaLogEntryCodec {

    /**
     * Format version of an entry record; the first byte of every queue element.
     */
    static final byte FORMAT_VERSION = 2;

    /**
     * The version byte and the id.
     */

    private final Path path;

    /**
     * @param path the log file, named in errors
     */
    FileSagaLogEntryCodec(Path path) {
        this.path = path;
    }

    byte[] serialize(SagaLogEntry entry) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(FORMAT_VERSION);
            out.writeLong(((FileSagaLogEntryId) entry.getId()).id());
            writeString(out, entry.getExecutionId());
            writeString(out, entry.getEntryType().name());
            writeString(out, entry.getNodeId());
            writeString(out, entry.getSagaName());
            writeString(out, entry.getJsonData());
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot serialize a saga-log entry for " + path, e);
        }
    }

    /**
     * @throws IllegalStateException if the record is in another format version
     * @throws UncheckedIOException  if the record is corrupt
     */
    SagaLogEntry deserialize(byte[] bytes) {
        checkVersion(bytes);
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes, 1, bytes.length - 1));
            SagaLogEntryBuilder builder = new SagaLogEntryBuilder();
            builder.id(new FileSagaLogEntryId(in.readLong()));
            builder.executionId(readString(in));
            builder.entryType(entryType(readString(in)));
            builder.nodeId(readString(in));
            builder.sagaName(readString(in));
            builder.jsonData(readString(in));
            return builder.build();
        } catch (IOException e) {
            throw new UncheckedIOException("Corrupt saga-log entry in " + path, e);
        }
    }

    private void checkVersion(byte[] bytes) {
        if (bytes.length == 0) {
            throw new UncheckedIOException("Corrupt saga-log entry in " + path, new IOException("Empty record"));
        }
        byte version = bytes[0];
        if (version != FORMAT_VERSION) {
            throw new IllegalStateException(String.format("Saga-log %s holds an entry in an unsupported format "
                    + "(version byte %d, expected %d). Logs written before 2.1.0 cannot be read: let their sagas "
                    + "finish with the old version, or delete the log file.", path, version, FORMAT_VERSION));
        }
    }

    private static SagaLogEntryType entryType(String name) throws IOException {
        try {
            return SagaLogEntryType.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("Unknown entry type " + name, e);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
            return;
        }
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(utf8.length);
        out.write(utf8);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length == -1) {
            return null;
        }
        if (length < -1 || length > in.available()) {
            throw new IOException("Bad string length " + length + ", " + in.available() + " bytes left in the record");
        }
        byte[] utf8 = new byte[length];
        in.readFully(utf8);
        return new String(utf8, StandardCharsets.UTF_8);
    }
}
