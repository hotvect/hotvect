package com.hotvect.api.data;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;

import static com.google.common.base.Preconditions.*;

/**
 * Containers for feature values that may need hashing
 */
public class RawValue {
    private static final EnumSet<RawValueType> ALLOWS_NUMERICALS = EnumSet.of(
            RawValueType.STRINGS_TO_NUMERICALS,
            RawValueType.SPARSE_VECTOR,
            RawValueType.DENSE_VECTOR
    );
    private final RawValueType valueType;

    private final double singleNumerical;
    private final SparseVector sparseVector;

    private final int singleCategorical;
    private final String singleString;
    private final String[] strings;
    private final double[] rawNumericals;

    private final int[] rawCategoricals;
    private volatile HashedValue cachedHashedValue;

    public void setCachedHashedValue(HashedValue cachedHashedValue) {
        this.cachedHashedValue = cachedHashedValue;
    }

    public HashedValue getCachedHashedValue() {
        return cachedHashedValue;
    }

    private RawValue(double singleNumerical, int singleCategorical, SparseVector sparseVector, String singleString, String[] strings, double[] rawNumericals, int[] rawCategoricals, RawValueType type) {
        this.singleNumerical = singleNumerical;
        this.singleCategorical = singleCategorical;
        this.sparseVector = sparseVector;
        this.singleString = singleString;
        this.strings = strings;
        this.rawNumericals = rawNumericals;
        this.rawCategoricals = rawCategoricals;
        this.valueType = type;
    }

    public static RawValue singleString(String singleString) {
        checkNotNull(singleString);
        return new RawValue(-1, -1, null, singleString, null, null, null, RawValueType.SINGLE_STRING);
    }

    public static RawValue strings(String[] strings) {
        checkNotNull(strings);
        return new RawValue(-1, -1, null, null, strings, null, null,RawValueType.STRINGS);
    }

    public static RawValue stringsToNumericals(String[] strings, double[] values) {
        checkArgument(strings.length == values.length,
                "Length of keys and values do not match:%s -> %s", Arrays.toString(strings), Arrays.toString(values));
        return new RawValue(-1, -1, null, null, strings, values, null,RawValueType.STRINGS_TO_NUMERICALS);
    }

    public static RawValue singleNumerical(double singleNumerical) {
        return new RawValue(singleNumerical, -1,  null, null, null, null, null,RawValueType.SINGLE_NUMERICAL);
    }

    public static RawValue singleCategorical(int singleCategorical) {
        return new RawValue(-1, singleCategorical,null, null, null, null, null,RawValueType.SINGLE_CATEGORICAL);
    }

    public static RawValue categoricals(int[] categoricals) {
        if(categoricals == null){
            throw new NullPointerException("Categoricals cannot be null");
        }
        return new RawValue(-1, -1, null, null, null, null, categoricals, RawValueType.CATEGORICALS);
    }

    /**
     * Please use {@link #sparseVector(int[], double[])}. There is no code change but the name is more intuitive.
     * @param names
     * @param values
     * @return
     */
    @Deprecated
    public static RawValue namedNumericals(int[] names, double[] values) {
        return sparseVector(names, values);
    }

    public static RawValue sparseVector(int[] names, double[] values) {
        checkArgument(names.length == values.length,
                "Length of keys and values do not match:%s -> %s", Arrays.toString(names), Arrays.toString(values));
        return new RawValue(-1, -1, new SparseVector(names, values), null, null, null, null, RawValueType.SPARSE_VECTOR);
    }

    public static RawValue denseVector(double[] values){
        checkNotNull(values,
                "Values cannot be null");
        return new RawValue(-1, -1, null, null, null, values, null, RawValueType.DENSE_VECTOR);
    }

    public int getSingleCategorical() {
        checkState(this.valueType == RawValueType.SINGLE_CATEGORICAL,
                "This value is type %s and not %s", getValueType(), RawValueType.SINGLE_CATEGORICAL);
        return this.singleCategorical;
    }

    public int[] getCategoricals() {
        checkState(this.valueType == RawValueType.CATEGORICALS,
                "No categoricals available for type:%s", this.valueType);
        return this.rawCategoricals;
    }

    public double getSingleNumerical() {
        checkState(this.valueType == RawValueType.SINGLE_NUMERICAL,
                "This value is type %s and not %s", this.getValueType(), RawValueType.SINGLE_NUMERICAL);
        return this.singleNumerical;
    }

    public int[] getNumericalIndices(){
        checkState(this.valueType == RawValueType.SPARSE_VECTOR,
                "No indices for numerical values available for type:%s", this.valueType);
        return this.sparseVector.getNumericalIndices();
    }

    public double[] getNumericals() {
        checkState(ALLOWS_NUMERICALS.contains(this.valueType),
                "No numericals available for type:%s", this.valueType);
        if (this.rawNumericals != null) {
            return this.rawNumericals;
        } else {
            return this.sparseVector.getNumericalValues();
        }
    }

    public String getSingleString() {
        checkState(this.valueType == RawValueType.SINGLE_STRING,
                "This value is type %s and not %s", this.getValueType(), RawValueType.SINGLE_STRING);
        return singleString;
    }

    public String[] getStrings() {
        checkState(this.valueType == RawValueType.STRINGS || this.valueType == RawValueType.STRINGS_TO_NUMERICALS,
                "Strings not available for this value type %s", this.getValueType());
        return strings;
    }

    public SparseVector getSparseVector() {
        if(this.sparseVector == null){
            throw new IllegalStateException("No sparse vector available for this value type:" + this.valueType);
        }
        return this.sparseVector;
    }

    public RawValueType getValueType() {
        return valueType;
    }

    public HashedValue getHashedValue() {
        switch (valueType) {
            case SINGLE_NUMERICAL:
                return HashedValue.singleNumerical(singleNumerical);
            case SINGLE_CATEGORICAL:
                return HashedValue.singleCategorical(singleCategorical);
            case CATEGORICALS:
                return HashedValue.categoricals(rawCategoricals);
            case SPARSE_VECTOR:
                return HashedValue.sparseVector(sparseVector.getNumericalIndices(), sparseVector.getNumericalValues());
            case DENSE_VECTOR:
                return HashedValue.denseVector(rawNumericals);
            default:
                throw new IllegalStateException("You cannot obtain a hashed value for type :" + valueType);
        }
    }

    @Override
    public String toString() {
        String header = "RawValue{type=" + valueType + ", value=";
        switch (valueType) {
            case SINGLE_NUMERICAL:
                return header + singleNumerical + "}";
            case SINGLE_CATEGORICAL:
                return header + singleCategorical + "}";
            case CATEGORICALS:
                return header + Arrays.toString(rawCategoricals) + "}";
            case CATEGORICALS_TO_NUMERICALS:
                return header + "(" + Arrays.toString(rawCategoricals) + " ," + Arrays.toString(rawNumericals) + ")" + "}";
            case SINGLE_STRING:
                return header + singleString + "}";
            case STRINGS:
                return header + Arrays.toString(strings) + "}";
            case STRINGS_TO_NUMERICALS:
                return header + "(" + Arrays.toString(getStrings()) + " ," + Arrays.toString(getNumericals()) + ")" + "}";
            case DENSE_VECTOR:
                return header + Arrays.toString(rawNumericals) + "}";
            case SPARSE_VECTOR:
                return header + sparseVector + "}";
            default: throw new AssertionError();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RawValue rawValue = (RawValue) o;
        return Double.compare(singleNumerical, rawValue.singleNumerical) == 0 && singleCategorical == rawValue.singleCategorical && valueType == rawValue.valueType && Objects.equals(sparseVector, rawValue.sparseVector) && Objects.equals(singleString, rawValue.singleString) && Arrays.equals(strings, rawValue.strings) && Arrays.equals(rawNumericals, rawValue.rawNumericals) && Arrays.equals(rawCategoricals, rawValue.rawCategoricals);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(valueType, singleNumerical, sparseVector, singleCategorical, singleString);
        result = 31 * result + Arrays.hashCode(strings);
        result = 31 * result + Arrays.hashCode(rawNumericals);
        result = 31 * result + Arrays.hashCode(rawCategoricals);
        return result;
    }
}
