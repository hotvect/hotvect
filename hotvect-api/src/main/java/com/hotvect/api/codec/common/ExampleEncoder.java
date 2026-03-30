package com.hotvect.api.codec.common;

import com.hotvect.api.data.common.Example;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public interface ExampleEncoder<EXAMPLE extends Example> extends Function<EXAMPLE, ByteBuffer> {

    /**
     * Used by algorithms that need a separate schema description, like catboost
     * @return
     */
    default Optional<String> schemaDescription(){
        return Optional.empty();
    }

    /**
     * Returns the file extension for encoded output files (e.g., ".tfrecord", ".tsv", ".jsonl").
     * The extension should include the leading dot.
     * Used by EncodeTask to construct proper output filenames with shard_%d pattern.
     *
     * <p>This method must be implemented by all encoders. Returning null or empty string
     * will cause encoding to fail with a clear error message.</p>
     *
     * @return the file extension including the leading dot (e.g., ".tfrecord")
     * @throws UnsupportedOperationException if the encoder does not implement this method
     */
    default String encodedFileExtension() {
        throw new UnsupportedOperationException(
            "Encoder " + this.getClass().getName() + " must implement encodedFileExtension(). " +
            "Return the file extension including leading dot (e.g., \".tfrecord\", \".tsv\", \".jsonl\")"
        );
    }

}
