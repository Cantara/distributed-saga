package no.cantara.saga.execution.adapter;

public interface ActionOutputSerializer<V> {

    Class<V> serializationClazz();

    String serialize(V data);

    V deserialize(String serializedData);
}
