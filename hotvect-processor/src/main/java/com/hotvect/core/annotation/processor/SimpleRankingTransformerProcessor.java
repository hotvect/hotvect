package com.hotvect.core.annotation.processor;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;

import com.hotvect.core.annotation.Feature;
import com.hotvect.core.annotation.GenerateSimpleRankingTransformer;
import com.hotvect.core.annotation.Inject;
import com.hotvect.core.annotation.InjectAlgorithm;
import com.hotvect.core.annotation.SharedFeature;
import com.hotvect.core.transform.ranking.SharedContext;
import com.hotvect.core.annotation.processor.analysis.GraphAnalyzer;
import com.hotvect.core.annotation.processor.codegen.SimpleRankingTransformerGenerator;
import com.hotvect.core.annotation.processor.model.FeatureSpec;
import com.hotvect.core.annotation.processor.model.Analysis;
import com.hotvect.core.annotation.processor.model.FeatureScanResult;
import com.hotvect.core.annotation.processor.model.TransformerSpec;
import com.hotvect.core.annotation.processor.report.MarkdownReportWriter;
import com.hotvect.core.annotation.processor.scan.FeatureScanner;
import com.hotvect.core.annotation.processor.scan.SpecReader;

@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class SimpleRankingTransformerProcessor extends AbstractProcessor {
    private ProcessingContext context;
    private final Set<String> processedSpecs = new HashSet<>();

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.context = new ProcessingContext(
                processingEnv.getMessager(),
                processingEnv.getFiler(),
                processingEnv.getElementUtils(),
                processingEnv.getTypeUtils(),
                processingEnv.getElementUtils().getTypeElement(SharedFeature.class.getCanonicalName()),
                processingEnv.getElementUtils().getTypeElement(Feature.class.getCanonicalName()),
                processingEnv.getElementUtils().getTypeElement(Inject.class.getCanonicalName()),
                processingEnv.getElementUtils().getTypeElement(InjectAlgorithm.class.getCanonicalName()),
                processingEnv.getElementUtils().getTypeElement(GenerateSimpleRankingTransformer.class.getCanonicalName()),
                processingEnv.getElementUtils().getTypeElement(SharedContext.class.getCanonicalName())
        );
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(GenerateSimpleRankingTransformer.class.getCanonicalName());
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }

        SpecReader specReader = new SpecReader(context);
        FeatureScanner featureScanner = new FeatureScanner(context);
        GraphAnalyzer graphAnalyzer = new GraphAnalyzer(context.types());
        MarkdownReportWriter reportWriter = new MarkdownReportWriter(context);
        SimpleRankingTransformerGenerator generator = new SimpleRankingTransformerGenerator(context);

        for (Element element : roundEnv.getElementsAnnotatedWith(context.specAnnotation())) {
            if (!(element instanceof TypeElement specElement)) {
                context.messager().printMessage(javax.tools.Diagnostic.Kind.ERROR,
                        "@GenerateSimpleRankingTransformer must target a class.", element);
                continue;
            }
            if (!processedSpecs.add(specElement.getQualifiedName().toString())) {
                continue;
            }

            TransformerSpec spec = specReader.readSpec(specElement);
            if (spec == null) continue;

            FeatureScanResult scanResult = featureScanner.scan(spec);
            Analysis analysis = graphAnalyzer.analyze(
                    scanResult.nodesByName(),
                    spec.outputFeatures().stream().map(FeatureSpec::name).toList());
            reportWriter.write(specElement, spec, scanResult, analysis);
            generator.generate(specElement, spec, scanResult, analysis);
        }

        return true;
    }
}
