package com.hotvect.api.state;


import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.BiConsumer;
import java.util.function.Function;

public interface StateCodec<STATE extends State> {
    Function<InputStream, STATE> getDeserializer();
    <SOURCE> BiConsumer<OutputStream, SOURCE> getSerializer();
}
