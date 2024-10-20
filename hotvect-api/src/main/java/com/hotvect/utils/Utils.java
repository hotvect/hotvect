package com.hotvect.utils;

import com.hotvect.api.data.CompoundNamespace;

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
            if (!Enum.class.isAssignableFrom(o.getClass()) &&
                            !CompoundNamespace.NamespaceId.class.isAssignableFrom(o.getClass())
            ){
                throw new IllegalArgumentException(
                        "Keys of this map must be an enum or a special object managed by hotvect. Please implement the interface using an enum, or use the compound namespace factories " +
                                " Offending class:" + o.getClass().getCanonicalName()
                );
            }
        }
    }

}
