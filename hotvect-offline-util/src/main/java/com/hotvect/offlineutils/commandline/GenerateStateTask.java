package com.hotvect.offlineutils.commandline;

import com.hotvect.api.algodefinition.state.StateGenerator;
import com.hotvect.api.algodefinition.state.StateGeneratorFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public class GenerateStateTask extends Task  {

    public GenerateStateTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        String generatorClassName = this.offlineTaskContext.algorithmDefinition().generateStateFactoryName();
        LOGGER.info("Running {} from {} to {} using {}", this.getClass().getSimpleName(), this.offlineTaskContext.options().sourceFiles, this.offlineTaskContext.options().destinationFile, generatorClassName);
        return withTaskMetadata(
                perform(),
                generatorClassName,
                offlineTaskContext.options(),
                this.getClass().getSimpleName());
    }

    static Map<String, Object> withTaskMetadata(
            Map<String, Object> generatorMetadata,
            String generatorClassName,
            Options options,
            String taskType) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (generatorMetadata != null) {
            metadata.putAll(generatorMetadata);
        }
        metadata.put("task_type", taskType);
        metadata.put("metadata_location", options.metadataLocation.toString());
        metadata.put("destination_file", options.destinationFile.toString());
        metadata.put("source_file", options.sourceFiles.toString());
        metadata.put("state_generator", generatorClassName);
        return metadata;
    }

    protected Map<String, Object> perform() throws Exception {
        String generatorClassName = this.offlineTaskContext.algorithmDefinition().generateStateFactoryName();
        if (generatorClassName == null) {
            throw new IllegalArgumentException("Algorithm definition must have generator_factory_classname for state generation");
        }

        StateGeneratorFactory stateGeneratorFactory = (StateGeneratorFactory) Class.forName(
                generatorClassName, true, offlineTaskContext.classLoader()
        ).getDeclaredConstructor().newInstance();

        StateGenerator stateGenerator = stateGeneratorFactory.getGenerator(offlineTaskContext.algorithmDefinition(), offlineTaskContext.classLoader());

        return stateGenerator.apply(this.offlineTaskContext.options().sourceFiles, this.offlineTaskContext.options().destinationFile);
    }

}
