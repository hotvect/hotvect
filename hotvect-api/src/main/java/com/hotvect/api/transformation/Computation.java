package com.hotvect.api.transformation;

import java.io.Serializable;
import java.util.function.Function;

@Deprecated(forRemoval = true)
public interface Computation<ARGUMENT, RETURN> extends Function<Computing<ARGUMENT>, RETURN>, Serializable {
    @Override
    RETURN apply(Computing<ARGUMENT> argument);
}
