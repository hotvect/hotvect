package com.hotvect.api.transformation.memoization;

import java.io.Serializable;
import java.util.function.Function;

public interface Computation<ARGUMENT, RETURN> extends Function<Computing<ARGUMENT>, RETURN>, Serializable {
    @Override
    RETURN apply(Computing<ARGUMENT> argument);
}
