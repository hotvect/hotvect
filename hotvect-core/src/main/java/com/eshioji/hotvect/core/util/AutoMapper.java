package com.eshioji.hotvect.core.util;

import com.eshioji.hotvect.api.data.DataRecord;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A utility class that copies over values across {@link DataRecord}s that uses different enum classes as key.
 * The value is only copied if the two enum keys have the same name (string representation)
 * @param <FROM>
 * @param <TO>
 * @param <V>
 */
public class AutoMapper<FROM extends Enum<FROM>, TO extends Enum<TO>, V> implements Function<DataRecord<FROM, V>, DataRecord<TO, V>> {

    private final DataRecord<FROM, TO> from2To;
    private final FROM[] toBeMapped;
    private final Class<TO> toNamespace;

    public AutoMapper(Class<FROM> fromNamespace, Class<TO> toNamespace) {
        this.toNamespace = toNamespace;
        List<TO> destinationsToMap = toAutoMap(fromNamespace, toNamespace);

        EnumMap<FROM, TO> autoMapping = new EnumMap<>(fromNamespace);
        Map<String, FROM> nameToFrom = toNames(fromNamespace);
        for (TO toNs : destinationsToMap) {
            FROM fromNs = nameToFrom.get(toNs.name());
            checkNotNull(fromNs);
            autoMapping.put(fromNs, toNs);
        }
        @SuppressWarnings("unchecked")
        FROM[] toMapKey = (FROM[]) Array.newInstance(fromNamespace, autoMapping.size());

        this.toBeMapped = autoMapping.keySet().toArray(toMapKey);
        this.from2To = new DataRecord<>(fromNamespace);
        for (Map.Entry<FROM, TO> entry : autoMapping.entrySet()) {
            this.from2To.put(entry.getKey(), entry.getValue());
        }

    }

    private Map<String, FROM> toNames(Class<FROM> targetEnum) {
        Map<String, FROM> ret = new HashMap<>();
        for (FROM from : targetEnum.getEnumConstants()) {
            ret.put(from.toString(), from);
        }
        return ImmutableMap.copyOf(ret);
    }

    private List<TO> toAutoMap(Class<FROM> from, Class<TO> to) {
        Set<String> fromNames = EnumSet.allOf(from).stream().map(Enum::toString).collect(Collectors.toSet());
        Set<String> toNames = EnumSet.allOf(to).stream().map(Enum::toString).collect(Collectors.toSet());
        Set<String> intersection = Sets.intersection(fromNames, toNames);
        return Arrays.stream(to.getEnumConstants())
                .filter(x -> intersection.contains(x.toString()))
                .collect(Collectors.toList());
    }

    /**
     * Copy values across {@link DataRecord}s with different {@link com.eshioji.hotvect.api.data.Namespace}s
     * @param input the input record
     * @return output record
     */
    @Override
    public DataRecord<TO, V> apply(DataRecord<FROM, V> input) {
        DataRecord<TO, V> to = new DataRecord<>(toNamespace);

        // Auto-mapping
        for (FROM f : toBeMapped) {
            V valueToMap = input.get(f);
            TO t = from2To.get(f);
            if (valueToMap != null) {
                to.put(t, valueToMap);
            }
        }

        return to;
    }

    /**
     * Query which enum key in the input will have its value copied over
     * @return the enum constants in the input, for which the values will be copied into the output
     */
    public EnumMap<FROM, TO> mapped() {
        return from2To.asEnumMap();
    }
}