package com.eshioji.hotvect.api.featurestate;


import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface FeatureStateCodec<STATE extends FeatureState> {
    Function<InputStream, STATE> getDeserializer();
    BiConsumer<OutputStream, STATE> getSerializer();
}
