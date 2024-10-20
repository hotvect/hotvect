package com.hotvect.core.audit;

import com.hotvect.api.audit.RawFeatureName;
import com.hotvect.api.data.HashedValue;
import com.hotvect.api.data.RawValue;
import com.hotvect.core.TestFeatureNamespace;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Disabled
class HasherAuditStateTest {

    @Test
    void single_categorical(){
        var subject = new HasherAuditState();
        subject.registerSourceRawValue(TestFeatureNamespace.single_categorical_1, RawValue.singleCategorical(-9), HashedValue.singleCategorical(-9));
        var state = subject.getFeatureName2SourceRawValue().get();
        var retrieved = state.get(new HashedFeatureName(TestFeatureNamespace.single_categorical_1, -9));
        assertEquals(retrieved, new RawFeatureName(TestFeatureNamespace.single_categorical_1, "-9"));
    }

    @Test
    void categoricals(){
        var subject = new HasherAuditState();
        subject.registerSourceRawValue(TestFeatureNamespace.categoricals_1, RawValue.categoricals(new int[]{1,1}), HashedValue.categoricals(new int[]{1,1}));
        var state = subject.getFeatureName2SourceRawValue().get();
        var retrieved = state.get(new HashedFeatureName(TestFeatureNamespace.categoricals_1, 1));
        assertEquals(retrieved, new RawFeatureName(TestFeatureNamespace.categoricals_1, "1"));
    }

    @Test
    void strings(){
        var subject = new HasherAuditState();
        subject.registerSourceRawValue(TestFeatureNamespace.strings_1, RawValue.strings(new String[]{"a"}), HashedValue.categoricals(new int[]{1}));
        var state = subject.getFeatureName2SourceRawValue().get();
        var retrieved = state.get(new HashedFeatureName(TestFeatureNamespace.strings_1, 1));
        assertEquals(retrieved, new RawFeatureName(TestFeatureNamespace.strings_1, "a"));
    }
    @Test
    void single_numerical(){
        var subject = new HasherAuditState();
        subject.registerSourceRawValue(TestFeatureNamespace.single_numerical_1, RawValue.singleNumerical(0.9), HashedValue.singleNumerical(0.9));
        var state = subject.getFeatureName2SourceRawValue().get();
        var retrieved = state.get(new HashedFeatureName(TestFeatureNamespace.single_numerical_1, 0));
        assertEquals(retrieved, new RawFeatureName(TestFeatureNamespace.single_numerical_1, "0"));
    }

    @Test
    void categorical_id_to_numericals(){
        var subject = new HasherAuditState();
        subject.registerSourceRawValue(TestFeatureNamespace.categorical_id_to_numericals_1, RawValue.namedNumericals(new int[]{1,2}, new double[]{1,2}), HashedValue.numericals(new int[]{1,2}, new double[]{1,2}));
        var state = subject.getFeatureName2SourceRawValue().get();
        var retrieved = state.get(new HashedFeatureName(TestFeatureNamespace.categorical_id_to_numericals_1, 1));
        assertEquals(retrieved, new RawFeatureName(TestFeatureNamespace.categorical_id_to_numericals_1, "1"));
    }

    @Test
    void single_string(){
        var subject = new HasherAuditState();
        subject.registerSourceRawValue(TestFeatureNamespace.single_string_1, RawValue.singleString("aaa"), HashedValue.singleCategorical(999));
        var state = subject.getFeatureName2SourceRawValue().get();
        var retrieved = state.get(new HashedFeatureName(TestFeatureNamespace.single_string_1, 999));
        assertEquals(retrieved, new RawFeatureName(TestFeatureNamespace.single_string_1, "aaa"));
    }

    @Test
    void string_to_numericals(){
        var subject = new HasherAuditState();
        subject.registerSourceRawValue(TestFeatureNamespace.string_to_numericals_1, RawValue.stringsToNumericals(new String[]{"aaa"}, new double[]{2.0}), HashedValue.numericals(new int[]{1}, new  double[]{2.0}));
        var state = subject.getFeatureName2SourceRawValue().get();
        var retrieved = state.get(new HashedFeatureName(TestFeatureNamespace.string_to_numericals_1, 1));
        assertEquals(retrieved, new RawFeatureName(TestFeatureNamespace.string_to_numericals_1, "aaa"));
    }

}