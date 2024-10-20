package com.hotvect.offlineutils.commandline;

import com.google.common.io.Files;
import com.hotvect.api.state.State;
import com.hotvect.api.state.StateCodec;
import com.hotvect.api.state.StateGenerator;
import com.hotvect.api.state.StateGeneratorFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.zip.GZIPOutputStream;

public class GenerateStateTask extends Task  {

    public GenerateStateTask(OfflineTaskContext offlineTaskContext) {
        super(offlineTaskContext);
    }

    @Override
    protected Map<String, Object> doCall() throws Exception {
        LOGGER.info("Running {} from {} to {} using ", this.getClass().getSimpleName(), this.offlineTaskContext.getOptions().sourceFiles, this.offlineTaskContext.getOptions().destinationFile);
        Map<String, Object> metadata = perform();
        metadata.put("task_type", this.getClass().getSimpleName());
        metadata.put("metadata_location", this.offlineTaskContext.getOptions().metadataLocation.toString());
        metadata.put("destination_file", this.offlineTaskContext.getOptions().destinationFile.toString());
        metadata.put("source_file", this.offlineTaskContext.getOptions().sourceFiles.toString());
        metadata.put("state_generator", this.offlineTaskContext.getOptions().generateStateTask);
        return metadata;
    }

    protected Map<String, Object> perform() throws Exception {
        StateGeneratorFactory<?> stateGeneratorFactory = (StateGeneratorFactory) Class.forName(
                this.offlineTaskContext.getOptions().generateStateTask, true, offlineTaskContext.getClassLoader()
        ).getDeclaredConstructor().newInstance();

        StateGenerator<?> stateGenerator = stateGeneratorFactory.getGenerator(offlineTaskContext.getAlgorithmDefinition(), offlineTaskContext.getClassLoader());
        State generatedState = stateGenerator.apply(this.offlineTaskContext.getOptions().sourceFiles);

        StateCodec stateCodec = stateGeneratorFactory.getCodec();

        long serializedStateSize = serializeToDestination(stateCodec.getSerializer(), generatedState);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("serialized_state_size", serializedStateSize);
        return metadata;
    }

    private long serializeToDestination(BiConsumer<OutputStream, State> serializer, State state){
        File dest = this.offlineTaskContext.getOptions().destinationFile;
        //noinspection UnstableApiUsage
        String ext = Files.getFileExtension(dest.toPath().getFileName().toString());
        boolean isDestGzip = "gz".equalsIgnoreCase(ext);

        try (FileOutputStream file = new FileOutputStream(dest);
             OutputStream sink = isDestGzip ? new GZIPOutputStream(file) : file
        ) {
            serializer.accept(sink, state);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return this.offlineTaskContext.getOptions().destinationFile.length();

    }

}
