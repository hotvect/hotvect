package com.hotvect.onlineutils.nativelibraries.catboost;

import ai.catboost.CatBoostError;
import ai.catboost.CatBoostModel;
import ai.catboost.CatBoostPredictions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.hotvect.utils.VerboseRunnable;
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.lang.ref.Cleaner;

import static com.google.common.base.Preconditions.checkState;

public class HotvectCatBoostModel implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(HotvectCatBoostModel.class);
    private static final Cleaner CLEANER = Cleaner.create(new ThreadFactoryBuilder().setNameFormat("catboost-model-cleaner-%s").build());

    private static Runnable getCleanResourceAction(final CatBoostModel catBoostModel) {
        return new VerboseRunnable() {
            @Override
            protected void doRun() throws Exception {
                catBoostModel.close();
                log.info("Cleaned up catboost model that had become unreachable, and was not closed explicitly");
            }
        };
    }

    private final Cleaner.Cleanable cleanable;
    private final CatBoostModel catBoostModel;

    private HotvectCatBoostModel(InputStream catboostModelParameters) {
        try {
            this.catBoostModel = CatBoostModel.loadModel(catboostModelParameters);
            this.cleanable = CLEANER.register(this, getCleanResourceAction(catBoostModel));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static HotvectCatBoostModel loadModel(InputStream catBoostModelParameters){
        return new HotvectCatBoostModel(catBoostModelParameters);
    }

    public double predict(@Nullable float[] numericFeatures, @Nullable String[] catFeatures, @Nullable String[] textFeatures, @Nullable float[][] embeddingFeatures) {
        try {
            CatBoostPredictions predictions = this.catBoostModel.predict(numericFeatures, catFeatures, textFeatures, embeddingFeatures);
            checkState(predictions.getObjectCount()==1);
            return predictions.get(0,0);
        } catch (CatBoostError e) {
            throw new RuntimeException(e);
        }
    }

    public double predict(@Nullable float[] numericFeatures, @Nullable String[] catFeatures, @Nullable String[] textFeatures) {
        return this.predict(numericFeatures, catFeatures, textFeatures, null);
    }

    public double predict(@Nullable float[] numericFeatures, @Nullable String[] catFeatures) {
        return this.predict(numericFeatures, catFeatures, null, null);
    }

    public double predict(@Nullable float[] numericFeatures) {
        return this.predict(numericFeatures, null, null, null);
    }


    public DoubleList predict(@Nullable float[][] numericFeatures, @Nullable String[][] catFeatures, @Nullable String[][] textFeatures, @Nullable float[][][] embeddingFeatures) {
        try {
            CatBoostPredictions predictions = this.catBoostModel.predict(numericFeatures, catFeatures, textFeatures, embeddingFeatures);
            int objectCount = predictions.getObjectCount();
            DoubleList ret = new DoubleArrayList(objectCount);
            for (int i = 0; i < objectCount; i++) {
                double rawFormulaVal = predictions.get(i, 0);
                ret.add(rawFormulaVal);
            }
            return ret;
        } catch (CatBoostError e) {
            throw new RuntimeException(e);
        }
    }

    public DoubleList predict(@Nullable float[][] numericFeatures, @Nullable String[][] catFeatures, @Nullable String[][] textFeatures) {
        return this.predict(numericFeatures, catFeatures, textFeatures, null);
    }

    public DoubleList predict(@Nullable float[][] numericFeatures, @Nullable String[][] catFeatures) {
        return this.predict(numericFeatures, catFeatures, null, null);
    }

    public DoubleList predict(@Nullable float[][] numericFeatures) {
        return this.predict(numericFeatures, null, null, null);
    }

    @Override
    public void close() throws Exception {
        this.cleanable.clean();
    }
}
