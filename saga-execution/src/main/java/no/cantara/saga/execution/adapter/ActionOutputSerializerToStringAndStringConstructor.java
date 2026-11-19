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

    @SuppressWarnings("unchecked")
    public V deserialize(String serializedData) {
        if (clazz.isAssignableFrom(String.class)) {
            return (V) serializedData; // safe unchecked cast, serialized data can be used directly
        }
        try {
            return clazz.getDeclaredConstructor(String.class).newInstance(serializedData);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error) {
                throw (Error) e.getCause();
            }
            throw cannotDeserialize(e.getCause());
        } catch (InstantiationException | IllegalAccessException | NoSuchMethodException e) {
            throw cannotDeserialize(e);
        }
    }

    private IllegalStateException cannotDeserialize(Throwable cause) {
        return new IllegalStateException("cannot deserialize " + clazz.getName() + " from its String constructor", cause);
    }
}
