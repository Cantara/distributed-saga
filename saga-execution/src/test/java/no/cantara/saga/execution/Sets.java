package no.cantara.saga.execution;

import java.util.LinkedHashSet;
import java.util.Set;

public class Sets {

    public static <V> Builder<V> set() {
        return new Builder();
    }

    public static class Builder<V> {
        private final LinkedHashSet<V> set = new LinkedHashSet<>();

        public LinkedHashSet<V> linkedHashSet() {
            return set;
        }

        public Builder add(V... values) {
            for (V v : values) {
                set.add(v);
            }
            return this;
        }

        public Builder add(Set<V>... values) {
            for (Set<V> v : values) {
                set.addAll(v);
            }
            return this;
        }

    }
}
