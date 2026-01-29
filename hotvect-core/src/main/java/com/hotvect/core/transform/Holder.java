package com.hotvect.core.transform;

/**
 * @param value This class is necessary to cache null (some transformations can perform expensive operations but then return null)
 */
public record Holder<OUT>(OUT value) {
}
