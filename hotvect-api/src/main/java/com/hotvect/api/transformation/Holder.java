package com.hotvect.api.transformation;

/**
 * @param value This class is necessary to cache null (some transformations can perform expensive operations but then return null
 */
@Deprecated(forRemoval = true)
public record Holder<OUT>(OUT value) {
}
