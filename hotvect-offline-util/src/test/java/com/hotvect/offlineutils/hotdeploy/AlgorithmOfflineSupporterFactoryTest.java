package com.hotvect.offlineutils.hotdeploy;

import com.hotvect.api.codec.common.ExampleEncoder;
import com.hotvect.api.data.OfflineRequest;
import com.hotvect.api.data.common.Example;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlgorithmOfflineSupporterFactoryTest {
    @Test
    void appendsHv9RecordDelimiterToLegacyStringEncoderOutput() {
        ByteBuffer encoded = AlgorithmOfflineSupporterFactory.normalizeEncodedRecord("0\tfeature", this);

        assertEquals("0\tfeature\n", StandardCharsets.UTF_8.decode(encoded).toString());
    }

    @Test
    void preservesHv9DelimiterBehaviorForAlreadyDelimitedLegacyStringOutput() {
        ByteBuffer encoded = AlgorithmOfflineSupporterFactory.normalizeEncodedRecord("0\tfeature\n", this);

        assertEquals("0\tfeature\n\n", StandardCharsets.UTF_8.decode(encoded).toString());
    }

    @Test
    void rejectsNonStringLegacyEncoderOutput() {
        ByteBuffer original = ByteBuffer.wrap("0\tfeature".getBytes(StandardCharsets.UTF_8));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AlgorithmOfflineSupporterFactory.normalizeEncodedRecord(original, this)
        );

        assertEquals(
                "Unsupported legacy encoded record type from encoder "
                        + this.getClass().getName()
                        + ": java.nio.HeapByteBuffer. Expected java.lang.String.",
                exception.getMessage()
        );
    }

    @Test
    void wrapsLegacyStringExampleEncoderOutputWithTrailingNewline() {
        ExampleEncoder<Example<? extends OfflineRequest, ?>> rawEncoder =
                legacyStringEncoder("0\tfeature", Optional.of("schema"));

        ExampleEncoder<Example<? extends OfflineRequest, ?>> encoder =
                AlgorithmOfflineSupporterFactory.adaptTrainEncoder(rawEncoder);

        assertEquals("0\tfeature\n", StandardCharsets.UTF_8.decode(encoder.apply(null)).toString());
        assertEquals(Optional.of("schema"), encoder.schemaDescription());
        assertThrows(UnsupportedOperationException.class, encoder::encodedFileExtension);
    }

    @Test
    void returnsCurrentExampleEncoderWithFileExtensionUnwrapped() {
        ExampleEncoder<Example<? extends OfflineRequest, ?>> rawEncoder = new ExampleEncoder<>() {
            @Override
            public ByteBuffer apply(Example<? extends OfflineRequest, ?> ignored) {
                return ByteBuffer.wrap("0\tfeature".getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public Optional<String> schemaDescription() {
                return Optional.of("schema");
            }

            @Override
            public String encodedFileExtension() {
                return ".txt";
            }
        };

        ExampleEncoder<Example<? extends OfflineRequest, ?>> encoder =
                AlgorithmOfflineSupporterFactory.adaptTrainEncoder(rawEncoder);

        assertSame(rawEncoder, encoder);
        assertEquals("0\tfeature", StandardCharsets.UTF_8.decode(encoder.apply(null)).toString());
        assertEquals(Optional.of("schema"), encoder.schemaDescription());
        assertEquals(".txt", encoder.encodedFileExtension());
    }

    @Test
    void detectsFileExtensionContract() {
        ExampleEncoder<Example<? extends OfflineRequest, ?>> encoder = new ExampleEncoder<>() {
            @Override
            public ByteBuffer apply(Example<? extends OfflineRequest, ?> ignored) {
                return ByteBuffer.allocate(0);
            }

            @Override
            public String encodedFileExtension() {
                return ".tsv";
            }
        };

        ExampleEncoder<Example<? extends OfflineRequest, ?>> missingExtensionEncoder = ignored -> ByteBuffer.allocate(0);

        assertTrue(AlgorithmOfflineSupporterFactory.hasEncodedFileExtension(encoder));
        assertFalse(AlgorithmOfflineSupporterFactory.hasEncodedFileExtension(missingExtensionEncoder));
    }

    @Test
    void rejectsBareFunctionEncoder() {
        Function<Example<? extends OfflineRequest, ?>, Object> rawEncoder = ignored -> "0\tfeature";

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> AlgorithmOfflineSupporterFactory.adaptTrainEncoder(rawEncoder)
        );

        assertEquals(
                "Encoder " + rawEncoder.getClass().getName() + " must implement ExampleEncoder.",
                exception.getMessage()
        );
    }

    @SuppressWarnings("unchecked")
    private static ExampleEncoder<Example<? extends OfflineRequest, ?>> legacyStringEncoder(
            String encodedRecord,
            Optional<String> schemaDescription
    ) {
        return (ExampleEncoder<Example<? extends OfflineRequest, ?>>) Proxy.newProxyInstance(
                AlgorithmOfflineSupporterFactoryTest.class.getClassLoader(),
                new Class<?>[]{ExampleEncoder.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "apply" -> encodedRecord;
                    case "schemaDescription" -> schemaDescription;
                    case "encodedFileExtension" -> throw new UnsupportedOperationException("legacy encoder");
                    case "toString" -> "LegacyStringEncoder";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException("Unexpected method: " + method);
                }
        );
    }
}
