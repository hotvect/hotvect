package com.hotvect.api.codec.common;

import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;

import java.util.List;
import java.util.function.Function;

/**
 * Decoder interface for converting JSON strings to Examples.
 * Since Examples are always offline data (containing outcomes), 
 * they always use OfflineRequest implementations.
 */
public interface ExampleDecoder<EXAMPLE extends Example<? extends OfflineRequest, ?>> extends Function<String, List<EXAMPLE>> {
}
