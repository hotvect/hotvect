package com.hotvect.vw.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.base.Joiner;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.audit.RawFeatureName;
import com.hotvect.api.state.StateCodec;
import com.hotvect.api.state.StateGenerator;
import com.hotvect.api.state.StateGeneratorFactory;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;


public class VwAuditGeneratorFactory implements StateGeneratorFactory<VwAuditState> {
    @Override
    public StateGenerator<VwAuditState> getGenerator(AlgorithmDefinition algorithmDefinition, ClassLoader classLoader) {
        try {
            return new VwAuditStateGenerator(algorithmDefinition, classLoader);
        } catch (MalformedAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public StateCodec<VwAuditState> getCodec() {
        ObjectMapper om = new ObjectMapper();
        Joiner joinOnHat = Joiner.on("^");
        return new StateCodec<>() {
            @Override
            public Function<InputStream, VwAuditState> getDeserializer() {
                throw new UnsupportedOperationException("not implemented");
            }

            @Override
            public BiConsumer<OutputStream, VwAuditState> getSerializer() {
                return (out, state) -> {
                    try(SequenceWriter writer = om.writer().withRootValueSeparator("\n").writeValues(out)){
                        for (Int2ObjectMap.Entry<VwAuditRecord> e : state.getState().int2ObjectEntrySet()) {
                            ObjectNode entry = om.createObjectNode();
                            entry.put("final_hash", e.getIntKey());
                            entry.put("weight", e.getValue().getWeight());
                            ArrayNode rawNames = om.createArrayNode();
                            entry.set("raw_names", rawNames);

                            boolean atLeastOneRawName = false;

                            for (List<RawFeatureName> rawFeatureNames : e.getValue().getRawFeatureNames()) {
                                atLeastOneRawName = true;
                                ObjectNode feature = om.createObjectNode();
                                String featureNamespace = joinOnHat.join(rawFeatureNames.stream().map(r -> r.getFeatureNamespace().toString()).iterator());
                                String featureRawname = joinOnHat.join(rawFeatureNames.stream().map(RawFeatureName::getSourceRawValue).iterator());
                                feature.put("feature_namespace", featureNamespace);
                                feature.put("feature_name", featureRawname);
                                rawNames.add(feature);
                            }
                            if(atLeastOneRawName){
                                writer.write(entry);
                            }
                        }

                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                };
            }
        };
    }
}
