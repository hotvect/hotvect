package com.hotvect.offlineutils.commandline;

import com.hotvect.api.algodefinition.state.StateGenerator;
import com.hotvect.api.algodefinition.state.StateGeneratorFactory;

import java.util.Map;

public class GenerateStateTask extends Task  {

    public GenerateStateTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {} using ", this.getClass().getSimpleName(), this.offlineTaskContext.options().sourceFiles, this.offlineTaskContext.options().destinationFile);
        Map<String, Object> metadata = perform();
        metadata.put("task_type", this.getClass().getSimpleName());
        metadata.put("metadata_location", this.offlineTaskContext.options().metadataLocation.toString());
        metadata.put("destination_file", this.offlineTaskContext.options().destinationFile.toString());
        metadata.put("source_file", this.offlineTaskContext.options().sourceFiles.toString());
        metadata.put("state_generator", this.offlineTaskContext.options().generateStateTask);
        return metadata;
    }

    protected Map<String, Object> perform() throws Exception {
        StateGeneratorFactory stateGeneratorFactory = (StateGeneratorFactory) Class.forName(
                this.offlineTaskContext.options().generateStateTask, true, offlineTaskContext.classLoader()
        ).getDeclaredConstructor().newInstance();

        StateGenerator stateGenerator = stateGeneratorFactory.getGenerator(offlineTaskContext.algorithmDefinition(), offlineTaskContext.classLoader());

        return stateGenerator.apply(this.offlineTaskContext.options().sourceFiles, this.offlineTaskContext.options().destinationFile);
    }

}
