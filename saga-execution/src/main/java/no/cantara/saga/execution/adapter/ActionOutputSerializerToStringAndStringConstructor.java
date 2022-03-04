package no.cantara.saga.execution.adapter;

import java.lang.reflect.InvocationTargetException;

public class ActionOutputSerializerToStringAndStringConstructor<V> implements ActionOutputSerializer<V> {

    private final Class<V> clazz;

    public ActionOutputSerializerToStringAndStringConstructor(Class<V> clazz) {
        this.clazz = clazz;
    }

    public Class<V> serializationClazz() {
        return clazz;
    }

    public String serialize(V data) {
        return String.valueOf(data);
    }

    public V deserialize(String serializedData) {
        if (clazz.isAssignableFrom(String.class)) {
            return (V) serializedData; // safe unchecked cast, serialized data can be used directly
        }
        try {
            return clazz.getDeclaredConstructor(String.class).newInstance(serializedData);
        } catch (InstantiationException | IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
