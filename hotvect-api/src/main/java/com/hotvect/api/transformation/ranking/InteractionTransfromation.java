package com.hotvect.api.transformation.ranking;

import com.hotvect.api.data.RawValue;

import java.util.function.BiFunction;

@Deprecated(forRemoval = true)
public interface InteractionTransfromation<SHARED, ACTION>  extends BiFunction<SHARED, ACTION, RawValue> {

}
