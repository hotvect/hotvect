package com.hotvect.onlineutils.hotdeploy.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;
import com.google.common.reflect.ClassPath;
import com.hotvect.api.algodefinition.AlgorithmDefinition;
import com.hotvect.api.algodefinition.AlgorithmId;
import com.hotvect.api.algodefinition.AlgorithmParameterMetadata;
import com.hotvect.utils.AlgorithmDefinitionReader;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

public class AlgorithmUtils {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private AlgorithmUtils() {
    }

    public static AlgorithmDefinition readAlgorithmDefinitionFromClassLoader(String algorithmName, ClassLoader classLoader) throws MalformedAlgorithmException {

        String algorithmDefinitionJsonPattern = "^"+Pattern.quote(algorithmName)+"-algorithm-definition\\.json$";
        try (InputStream is = findFirstMatchingResourceAsStream(algorithmDefinitionJsonPattern, classLoader)) {
            String algoDefJson = CharStreams.toString(new InputStreamReader(is, Charsets.UTF_8));

            AlgorithmDefinition ret = new AlgorithmDefinitionReader().parse(algoDefJson);
            checkState(algorithmName.equals(ret.getAlgorithmId().getAlgorithmName()),
                    "Read algorithm name %s does not match specified algorithm %s", ret.getAlgorithmId());
            return ret;
        } catch (IOException e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    private static InputStream findFirstMatchingResourceAsStream(String regex, ClassLoader classLoader) throws MalformedAlgorithmException {
        try {
            Pattern anyAlgoDefPattern = Pattern.compile(".*-algorithm-definition\\.json$");
            Set<String> algoDefsFoundForDebug = new HashSet<>();
            Pattern pattern = Pattern.compile(regex);
            ClassPath classPath = ClassPath.from(classLoader);

            for (ClassPath.ResourceInfo resourceInfo : classPath.getResources()) {
                String resourceName = resourceInfo.getResourceName();
                if (pattern.matcher(resourceName).matches()) {
                    InputStream is = classLoader.getResourceAsStream(resourceName);
                    return checkNotNull(is, resourceName + " is unexpectedly null");
                } else if (anyAlgoDefPattern.matcher(resourceName).matches()) {
                    algoDefsFoundForDebug.add(resourceName);
                }
            }
            throw new MalformedAlgorithmException("Cannot find resource matching " + regex + " in classpath. Available algorithm-definition files:" + algoDefsFoundForDebug);
        }catch (IOException e){
            throw new MalformedAlgorithmException(e);
        }
    }

    public static AlgorithmParameterMetadata readAlgorithmParameterMetadata(AlgorithmId algorithmId, File parameterFile, boolean strictAlgorithmVersionCheck) throws MalformedAlgorithmException {
        checkState(parameterFile != null, "Parameter file is required, but was not supplied");
        checkState(parameterFile.exists(), "Specified parameter file %s does not exist", parameterFile);
        try (ZipFile parameterFileIn = new ZipFile(parameterFile)) {
            // TODO optional version suffix is supported for legacy reasons - when hotvect v4 algorithm disappear, we can remove it
            String pattern = "^" + Pattern.quote(algorithmId.getAlgorithmName()) + "(@[\\w\\-\\.]+)?\\/algorithm-parameters\\.json$";
            JsonNode parsed = new ObjectMapper().readTree(ZipFiles.readFromZipFirstMatching(parameterFileIn, pattern));
            var ret = new AlgorithmParameterMetadata(
                    checkNotNull(parsed.get("algorithm_name").asText(), "algorithm_name not found"),
                    checkNotNull(parsed.get("algorithm_version").asText(), "algorithm_version not found"),
                    checkNotNull(parsed.get("parameter_id").asText(), "parameter_id not found"),
                    checkNotNull(ZonedDateTime.parse(parsed.get("ran_at").asText()).toInstant(), "ran_at not found"),
                    Optional.ofNullable(parsed.get("last_training_time"))
                            .map(JsonNode::asText)
                            .map(ZonedDateTime::parse)
                            .map(ZonedDateTime::toInstant)
            );
            if (strictAlgorithmVersionCheck){
                checkState(ret.getAlgorithmId().equals(algorithmId),
                        "AlgorithmID read from algorithm-parameters.json does not match. Specified:%s read:%s", algorithmId, ret.getAlgorithmId());
            }
            return ret;
        } catch (IOException e) {
            throw new MalformedAlgorithmException(e);
        }
    }

    public static Map<String, InputStream> extractParameters(AlgorithmId algorithmId, ZipFile parameterFile) {
        String pattern = "^" + Pattern.quote(algorithmId.getAlgorithmName()) + "(@[\\w\\-\\.]+)?\\/.+";
        return ZipFiles.readFromZipMatching(parameterFile, pattern);
    }
}
