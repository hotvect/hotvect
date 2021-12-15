package com.eshioji.hotvect.api.featurestate;


import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface FeatureStateCodec<S extends FeatureState> {
    Function<InputStream, S> getDeserializer();
    BiConsumer<OutputStream, S> getSerializer();
}
