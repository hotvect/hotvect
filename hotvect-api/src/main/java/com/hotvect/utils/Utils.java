package com.hotvect.utils;

import com.hotvect.api.data.CompoundNamespace;

import java.util.Collection;
import java.util.Map;

/**
 * This class was moved to hotvect-core
 */
@Deprecated(forRemoval = true)
public class Utils {
    private Utils() {
    }

    @Deprecated(forRemoval = true)
    public static void checkKeysAreEnums(Map<?, ?> map) {
        for (Object o : map.keySet()) {
            if (!Enum.class.isAssignableFrom(o.getClass())) {
                throw new IllegalArgumentException(
                        "Keys of this map must be an enum. Please implement the interface using an enum. " +
                                " Offending class:" + o.getClass().getCanonicalName()
                );
            }
        }
    }


    @Deprecated(forRemoval = true)
    public static void checkCollectionIsEnumsOrNamespaceIdObjects(Collection<?> collection) {
        for (Object o : collection) {
            Class<?> clazz = o.getClass();
            if (!(Enum.class.isAssignableFrom(clazz) ||
                    CompoundNamespace.NamespaceId.class.isAssignableFrom(clazz) ||
                    CompoundNamespace.FeatureNamespaceId.class.isAssignableFrom(clazz))) {
                throw new IllegalArgumentException(
                        "Collection elements must be an enum or a special object managed by hotvect. " +
                                "Offending class: " + clazz.getCanonicalName()
                );
            }
        }
    }
}
