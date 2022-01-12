package com.eshioji.hotvect.core.transform.ranking;

import com.eshioji.hotvect.api.data.RawValue;

import java.util.function.BiFunction;

public interface InteractionTransfromation<SHARED, ACTION>  extends BiFunction<SHARED, ACTION, RawValue> {

}
