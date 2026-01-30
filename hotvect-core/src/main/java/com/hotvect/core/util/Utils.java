package com.hotvect.core.util;

import com.hotvect.api.data.CompoundNamespace;
import com.hotvect.core.transform.Namespaces;

import java.util.Collection;
import java.util.Map;

public class Utils {
    private Utils() {
    }

    @Deprecated
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



    public static void checkCollectionIsEnumsOrNamespaceIdObjects(Collection<?> collection) {
        for (Object o : collection) {
            Class<?> clazz = o.getClass();
            if (!(Enum.class.isAssignableFrom(clazz) ||
                    Namespaces.NamespaceId.class.isAssignableFrom(clazz) ||
                    Namespaces.FeatureNamespaceId.class.isAssignableFrom(clazz) ||
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
