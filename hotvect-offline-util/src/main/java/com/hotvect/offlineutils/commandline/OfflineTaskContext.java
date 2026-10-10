package com.hotvect.offlineutils.commandline;

import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.onlineutils.serving.EmsPredictionRuntime;
import com.hotvect.onlineutils.serving.FixedCompositionRuntime;
import com.hotvect.onlineutils.util.Closeables;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** Resources and one explicit algorithm source owned by an offline task. */
public final class OfflineTaskContext implements AutoCloseable {
    private final MeterRegistry meterRegistry;
    private final Options options;
    private final RuntimeSource source;

    sealed interface RuntimeSource permits DirectRuntime, FixedRuntime, EmsRuntime {
        AlgorithmDefinition representativeDefinition();
    }

    record DirectRuntime(
            URLClassLoader classLoader,
            OfflineAlgorithmSource.Direct algorithmSource,
            AlgorithmDefinition representativeDefinition,
            Optional<AutoCloseable> parentClassLoaderOwner) implements RuntimeSource {
        DirectRuntime {
            Objects.requireNonNull(classLoader, "classLoader must not be null");
            Objects.requireNonNull(algorithmSource, "algorithmSource must not be null");
            Objects.requireNonNull(representativeDefinition, "representativeDefinition must not be null");
            Objects.requireNonNull(parentClassLoaderOwner, "parentClassLoaderOwner must not be null");
        }
    }

    record FixedRuntime(
            FixedCompositionRuntime runtime,
            AlgorithmDefinition representativeDefinition,
            Optional<AutoCloseable> parentClassLoaderOwner) implements RuntimeSource {
        FixedRuntime {
            Objects.requireNonNull(runtime, "runtime must not be null");
            Objects.requireNonNull(representativeDefinition, "representativeDefinition must not be null");
            Objects.requireNonNull(parentClassLoaderOwner, "parentClassLoaderOwner must not be null");
        }
    }

    record EmsRuntime(
            EmsPredictionRuntime runtime,
            AlgorithmDefinition representativeDefinition,
            Optional<AutoCloseable> parentClassLoaderOwner,
            String stateSource,
            String rootSlot,
            String assignmentKeyJsonPointer) implements RuntimeSource {
        EmsRuntime {
            Objects.requireNonNull(runtime, "runtime must not be null");
            Objects.requireNonNull(representativeDefinition, "representativeDefinition must not be null");
            Objects.requireNonNull(parentClassLoaderOwner, "parentClassLoaderOwner must not be null");
            Objects.requireNonNull(stateSource, "stateSource must not be null");
            Objects.requireNonNull(rootSlot, "rootSlot must not be null");
            Objects.requireNonNull(assignmentKeyJsonPointer, "assignmentKeyJsonPointer must not be null");
        }
    }

    public OfflineTaskContext(
            URLClassLoader classLoader,
            MeterRegistry meterRegistry,
            Options options,
            AlgorithmDefinition algorithmDefinition) {
        this(
                meterRegistry,
                options,
                new DirectRuntime(
                        classLoader,
                        directAlgorithmSource(options),
                        algorithmDefinition,
                        Optional.empty()));
    }

    static OfflineTaskContext forDirect(
            URLClassLoader classLoader,
            MeterRegistry meterRegistry,
            Options options,
            AlgorithmDefinition algorithmDefinition,
            AutoCloseable parentClassLoaderOwner) {
        return new OfflineTaskContext(
                meterRegistry,
                options,
                new DirectRuntime(
                        classLoader,
                        directAlgorithmSource(options),
                        algorithmDefinition,
                        Optional.ofNullable(parentClassLoaderOwner)));
    }

    static OfflineTaskContext forEmsPrediction(
            MeterRegistry meterRegistry,
            Options options,
            AlgorithmDefinition representativeDefinition,
            EmsPredictionRuntime runtime,
            AutoCloseable parentClassLoaderOwner,
            String emsStateSource,
            String rootSlot,
            String assignmentKeyJsonPointer) {
        return new OfflineTaskContext(
                meterRegistry,
                options,
                new EmsRuntime(
                        runtime,
                        representativeDefinition,
                        Optional.ofNullable(parentClassLoaderOwner),
                        emsStateSource,
                        rootSlot,
                        assignmentKeyJsonPointer));
    }

    static OfflineTaskContext forFixedComposition(
            MeterRegistry meterRegistry,
            Options options,
            AlgorithmDefinition rootDefinition,
            FixedCompositionRuntime runtime,
            AutoCloseable parentClassLoaderOwner) {
        return new OfflineTaskContext(
                meterRegistry,
                options,
                new FixedRuntime(
                        runtime,
                        rootDefinition,
                        Optional.ofNullable(parentClassLoaderOwner)));
    }

    private OfflineTaskContext(
            MeterRegistry meterRegistry,
            Options options,
            RuntimeSource source) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.options = Objects.requireNonNull(options, "options must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
    }

    private static OfflineAlgorithmSource.Direct directAlgorithmSource(Options options) {
        OfflineAlgorithmSource algorithmSource = Objects.requireNonNull(
                Objects.requireNonNull(options, "options must not be null").algorithmSource,
                "algorithmSource must not be null");
        if (algorithmSource instanceof OfflineAlgorithmSource.Direct direct) {
            return direct;
        }
        throw new IllegalArgumentException("Direct runtime requires a direct algorithm source");
    }

    RuntimeSource source() {
        return source;
    }

    DirectRuntime directRuntime() {
        if (source instanceof DirectRuntime direct) {
            return direct;
        }
        throw new IllegalStateException("Composed execution does not use a direct algorithm source");
    }

    public URLClassLoader classLoader() {
        return directRuntime().classLoader();
    }

    public MeterRegistry meterRegistry() {
        return meterRegistry;
    }

    public Options options() {
        return options;
    }

    public AlgorithmDefinition algorithmDefinition() {
        return source.representativeDefinition();
    }

    Path localStateRoot() {
        return Path.of(System.getProperty("java.io.tmpdir"), "algorithm-state").toAbsolutePath();
    }

    @Override
    public void close() {
        switch (source) {
            case DirectRuntime direct -> Closeables.closeAll(
                    "Could not close offline task context",
                    direct.classLoader(),
                    direct.parentClassLoaderOwner().orElse(null));
            case FixedRuntime fixed -> Closeables.closeAll(
                    "Could not close offline task context",
                    fixed.runtime(),
                    fixed.parentClassLoaderOwner().orElse(null));
            case EmsRuntime ems -> Closeables.closeAll(
                    "Could not close offline task context",
                    ems.runtime(),
                    ems.parentClassLoaderOwner().orElse(null));
        }
    }
}
