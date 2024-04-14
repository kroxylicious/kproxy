/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.filter.encryption.common;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Abstract implementation of {@link Resolver}.
 * @param <E> The enum naming the implementations
 * @param <T> The interface that the implementations conform to
 * @param <S> The self type of the subclass implementing this class.
 */
public abstract class AbstractResolver<E extends Enum<E>, T extends PersistedIdentifiable<E>, S extends AbstractResolver<E, T, S>>
        implements Resolver<E, T> {
    private final Map<E, T> nameMapping;
    private final Map<Byte, T> idMapping;

    protected AbstractResolver(Collection<T> impls) {
        if (Objects.requireNonNull(impls).isEmpty()) {
            throw new IllegalArgumentException("impls cannot be empty");
        }
        // each T can have multiple names, but each name must uniquely identify a T
        // each T has a unique id
        this.nameMapping = impls.stream().collect(Collectors.toMap(T::name, tx -> tx));
        if (nameMapping.size() < impls.size()) {
            throw new IllegalArgumentException();
        }
        this.idMapping = impls.stream().collect(Collectors.toMap(T::serializedId, tx -> tx));
        if (idMapping.size() != impls.size()) {
            throw new IllegalArgumentException();
        }
        var reverseIdMapping = impls.stream().collect(Collectors.toMap(tx -> tx, T::serializedId));
        if (idMapping.size() != reverseIdMapping.size()) {
            throw new IllegalArgumentException();
        }
    }

    /**
     * Create a new instance of the implementation class of {@link AbstractResolver}.
     * @param values The values
     * @return A new instance
     */
    protected abstract S newInstance(Collection<T> values);

    /**
     * To be overridden if a subclass needs to throw a different exception than {@link EncryptionException} when a lookup fails.
     * @param msg The message
     * @return The exception to be thrown.
     */
    protected RuntimeException newException(String msg) {
        throw new EncryptionException(msg);
    }

    @SafeVarargs
    protected final S subset(E... e) {
        var m = new HashMap<>(nameMapping);
        m.keySet().retainAll(Arrays.asList(e));
        return newInstance(m.values());
    }

    @NonNull
    private String enumName() {
        return nameMapping.keySet().iterator().next().getDeclaringClass().getSimpleName();
    }

    @Override
    public @NonNull T fromName(@NonNull E element) {
        Objects.requireNonNull(element);
        var impl = nameMapping.get(element);
        if (impl == null) {
            throw newException("Unknown " + enumName() + " name: " + element);
        }
        return impl;
    }

    @Override
    public @NonNull T fromSerializedId(byte id) {
        Objects.requireNonNull(id);
        var impl = idMapping.get(id);
        if (impl == null) {
            throw newException("Unknown " + enumName() + " id: " + id);
        }
        return impl;
    }

    public byte toSerializedId(@NonNull T impl) {
        return impl.serializedId();
    }

}
