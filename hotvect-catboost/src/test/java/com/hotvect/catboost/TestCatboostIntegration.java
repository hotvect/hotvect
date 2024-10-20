package com.hotvect.catboost;

import com.hotvect.onlineutils.nativelibraries.catboost.HotvectCatBoostModel;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestCatboostIntegration {

    @Disabled("Do not have the binary model right now, will be added later")
    @Test
    public void test() throws Exception {
        HotvectCatBoostModel model = HotvectCatBoostModel.loadModel(TestCatboostIntegration.class.getResourceAsStream("model.bin"));
        float[][] numericals = new float[][]{
                new float[]{6.0f},
                new float[]{7.0f},
                new float[]{8.0f},
                new float[]{1.0f},
                new float[]{2.0f},
                new float[]{3.0f},
        };
        String[][] categoricals = new String[][]{
                new String[]{"a", "x"},
                new String[]{"b", "y"},
                new String[]{"c", "x"},
                new String[]{"a", "y"},
                new String[]{"b", "x"},
                new String[]{"c", "y"},
        };
        DoubleList pred = model.predict(numericals, categoricals);

        double[] actual = IntStream.range(0, 6).mapToDouble(pred::get).toArray();

        double[] expected = new double[]{
                0.7781264507,
                0.7830211821,
                0.8057087514,
                0.05117318985,
                0.1253990494,
                0.01130173592
        };

        assertArrayEquals(expected, actual, 0.000001);
    }
}
