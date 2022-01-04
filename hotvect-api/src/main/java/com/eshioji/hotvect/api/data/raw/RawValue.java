package com.eshioji.hotvect.api.data.raw;

import com.eshioji.hotvect.api.data.SparseVector;
import com.eshioji.hotvect.api.data.hashed.HashedValue;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;

import static com.google.common.base.Preconditions.*;

/**
 * Containers for feature values that may need hashing
 */
public class RawValue {
    private static final EnumSet<RawValueType> ALLOWS_CATEGORICALS = EnumSet.of(
            RawValueType.SINGLE_CATEGORICAL,
            RawValueType.CATEGORICALS
    );
    private static final EnumSet<RawValueType> ALLOWS_NUMERICALS = EnumSet.of(
            RawValueType.SINGLE_NUMERICAL,
            RawValueType.CATEGORICALS_TO_NUMERICALS,
            RawValueType.STRINGS_TO_NUMERICALS
    );
    private final RawValueType valueType;
    private final HashedValue hashedValue;
    private final String singleString;
    private final String[] strings;
    private final double[] rawNumericals;

    private RawValue(HashedValue hashedValue, String singleString, String[] strings, double[] rawNumericals, RawValueType type) {
        this.hashedValue = hashedValue;
        this.singleString = singleString;
        this.strings = strings;
        this.rawNumericals = rawNumericals;
        this.valueType = type;
    }

    public static RawValue singleString(String singleString) {
        checkNotNull(singleString);
        return new RawValue(null, singleString, null, null, RawValueType.SINGLE_STRING);
    }

    public static RawValue strings(String[] strings) {
        checkNotNull(strings);
        return new RawValue(null, null, strings, null, RawValueType.STRINGS);
    }

    public static RawValue stringsToNumericals(String[] strings, double[] values) {
        checkArgument(strings.length == values.length,
                "Length of keys and values do not match:%s -> %s", Arrays.toString(strings), Arrays.toString(values));
        return new RawValue(null, null, strings, values, RawValueType.STRINGS_TO_NUMERICALS);
    }

    public static RawValue singleNumerical(double singleNumerical) {
        return new RawValue(HashedValue.singleNumerical(singleNumerical), null, null, null, RawValueType.SINGLE_NUMERICAL);
    }

    public static RawValue singleCategorical(int singleCategorical) {
        return new RawValue(HashedValue.singleCategorical(singleCategorical), null, null, null, RawValueType.SINGLE_CATEGORICAL);
    }

    public static RawValue categoricals(int[] categoricals) {
        return new RawValue(HashedValue.categoricals(categoricals), null, null, null, RawValueType.CATEGORICALS);
    }

    public static RawValue namedNumericals(int[] names, double[] values) {
        checkArgument(names.length == values.length,
                "Length of keys and values do not match:%s -> %s", Arrays.toString(names), Arrays.toString(values));
        return new RawValue(HashedValue.numericals(names, values), null, null, null, RawValueType.CATEGORICALS_TO_NUMERICALS);
    }

    public int getSingleCategorical() {
        checkState(this.valueType == RawValueType.SINGLE_CATEGORICAL,
                "This value is type %s and not %s", getValueType(), RawValueType.SINGLE_CATEGORICAL);
        return hashedValue.getSingleCategorical();
    }

    public int[] getCategoricals() {
        checkState(ALLOWS_CATEGORICALS.contains(this.valueType),
                "No categoricals available for type:%s", this.valueType);
        return hashedValue.getCategoricalIndices();
    }

    public double getSingleNumerical() {
        checkState(this.valueType == RawValueType.SINGLE_NUMERICAL,
                "This value is type %s and not %s", this.getValueType(), RawValueType.SINGLE_NUMERICAL);
        return hashedValue.getSingleNumerical();
    }

    public int[] getNumericalIndices(){
        checkState(ALLOWS_NUMERICALS.contains(this.valueType),
                "No numericals available for type:%s", this.valueType);
        return checkNotNull(hashedValue).getNumericalIndices();
    }

    public double[] getNumericals() {
        checkState(ALLOWS_NUMERICALS.contains(this.valueType),
                "No numericals available for type:%s", this.valueType);
        if(hashedValue != null) {
            return hashedValue.getNumericals();
        } else {
            return this.rawNumericals;
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
        return this.hashedValue.asSparseVector();
    }

    public RawValueType getValueType() {
        return valueType;
    }

    public HashedValue getHashedValue() {
        return this.hashedValue;
    }

    @Override
    public String toString() {
        String header = "RawValue{type=" + valueType + ", value=";
        switch (valueType) {
            case SINGLE_NUMERICAL:
            case SINGLE_CATEGORICAL:
            case CATEGORICALS:
            case CATEGORICALS_TO_NUMERICALS:
                return header + hashedValue + "}";
            case SINGLE_STRING: return header + singleString + "}";
            case STRINGS: return header + Arrays.toString(strings) + "}";
            case STRINGS_TO_NUMERICALS: return header + "(" + Arrays.toString(getStrings()) + " ," + Arrays.toString(getNumericals()) + ")" + "}";
            default: throw new AssertionError();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RawValue that = (RawValue) o;

        // Either this value does not have numericals, or they are equal
        boolean numericalEquals = !ALLOWS_NUMERICALS.contains(this.getValueType()) || Arrays.equals(getNumericals(), that.getNumericals());

        return valueType == that.valueType &&
                Objects.equals(hashedValue, that.hashedValue) &&
                Objects.equals(singleString, that.singleString) &&
                Arrays.equals(strings, that.strings) && numericalEquals;

    }

    @Override
    public int hashCode() {
        int result = Objects.hash(valueType, hashedValue, singleString);
        result = 31 * result + Arrays.hashCode(strings);
        if (ALLOWS_NUMERICALS.contains(this.getValueType())){
            result = 31 * result + Arrays.hashCode(getNumericals());
        }
        return result;
    }
}
