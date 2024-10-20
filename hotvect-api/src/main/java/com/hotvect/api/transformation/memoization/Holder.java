package com.hotvect.api.transformation.memoization;

class Holder<OUT> {
    // This class is necessary to cache null (some transformations can perform expensive operations
    // but then return null
    final OUT value;

    Holder(OUT value) {
        this.value = value;
    }
}
