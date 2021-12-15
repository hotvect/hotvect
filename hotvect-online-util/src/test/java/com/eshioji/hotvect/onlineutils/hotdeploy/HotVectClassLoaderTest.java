package com.eshioji.hotvect.onlineutils.hotdeploy;

import com.eshioji.hotvect.api.codec.ExampleDecoder;
import com.eshioji.hotvect.api.data.hashed.HashedValue;
import com.eshioji.hotvect.api.data.raw.Example;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.*;

class HotVectClassLoaderTest {

    /**
     * We use a very old version of guava, and try to use classes that are no longer present in newer versions
     * @throws Exception
     */
    @Test
    void loadClass() throws Exception {
        URL oldGuavaJar = this.getClass().getResource("guava-r03.jar");

        HotVectClassLoader subject = new HotVectClassLoader(new URL[]{oldGuavaJar});

        Class<?> veryOldGuavaClass = subject.loadClass("com.google.common.util.concurrent.ValueFuture");
        Object veryOldGuavaObject = veryOldGuavaClass.getDeclaredMethod("create").invoke(null);

        Method set = veryOldGuavaClass.getMethod("set", Object.class);
        set.invoke(veryOldGuavaObject, (ExampleDecoder<HashedValue>) s -> {
            return ImmutableList.of(new Example<>(HashedValue.singleCategorical(s.length()), 99.0));
        });

        Method get = veryOldGuavaClass.getMethod("get");
        ExampleDecoder<HashedValue> decoder = (ExampleDecoder<HashedValue>) get.invoke(veryOldGuavaObject);

        Example<HashedValue> example = decoder.apply("dummy").get(0);
        assertEquals(99.0, example.getTarget());
        assertEquals(5, example.getRecord().getSingleCategorical());

    }
}