package com.hotvect.utils;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Utility class used to obtain "additional_properties" from user defined objects like SHARED, ACTION, OUTCOME objects.
 * Often these objects are POJOs generated from JSON schema and have a dedicated "additional_properties" field where
 * the algorithm developer can store various additional data.
 */
public class AdditionalProperties {
    private static final ThreadLocal<IdentityHashMap<Class<?>, Optional<Method>>> ADDITIONAL_PROPERTIES_GETTER_CACHE = ThreadLocal.withInitial(IdentityHashMap::new);
    private AdditionalProperties(){}

    @SafeVarargs
    public static Map<String, Object> mergeAdditionalProperties(Map<String, Object>... properties) {
        Map<String, Object> ret = new HashMap<>();
        for (Map<String, Object> ps : properties) {
            if(ps!=null){
                ret.putAll(ps);
            }
        }
        return ret;
    }

    public static Map<String, Object> getAdditionalProperties(Object object) {
        if(object == null){
            return Collections.emptyMap();
        }

        try {
            Optional<Method> getter = getGetter(object);

            if (getter.isPresent()) {
                Map<String, Object> ret = (Map<String, Object>) getter.get().invoke(object);
                return ret != null ? ret : Collections.emptyMap();
            } else {
                // No additional properties
                return Collections.emptyMap();
            }
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }

    private static Optional<Method> getGetter(Object object) {

        Optional<Method> ret = ADDITIONAL_PROPERTIES_GETTER_CACHE.get().get(object.getClass());
        if (ret != null) {
            // If we already looked and have the method cached if available, return that
            return ret;
        }

        // We haven't looked yet if the method is available
        ret = Optional.empty();
        for (String name : List.of("getAdditionalProperties", "additionalProperties")) {
            try {
                Method method = object.getClass().getMethod(name);
                method.setAccessible(true);
                ret = Optional.of(method);
                break;
            } catch (NoSuchMethodException ignored) {
            }
        }
        ADDITIONAL_PROPERTIES_GETTER_CACHE.get().put(object.getClass(), ret);
        return ret;
    }

}
