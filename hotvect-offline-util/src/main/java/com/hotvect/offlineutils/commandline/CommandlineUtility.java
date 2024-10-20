package com.hotvect.offlineutils.commandline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.hotvect.onlineutils.hotdeploy.util.MalformedAlgorithmException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Optional;

public class CommandlineUtility {
    private static final ObjectMapper OM = new ObjectMapper();
    private CommandlineUtility(){}

    public static void expandTildaOnFileFields(Object optionObject) throws IllegalAccessException {
        for (Field field : optionObject.getClass().getFields()) {
            // Check if the field is of type File
            if (field.getType().equals(File.class)) {
                File file = (File) field.get(optionObject);

                if (file != null) {
                    String filePath = file.getPath();
                    if (filePath.startsWith("~" + File.separator)) {
                        filePath = System.getProperty("user.home") + filePath.substring(1);
                        File expandedFile = new File(filePath);
                        field.set(optionObject, expandedFile);
                    }
                }
            }
        }
    }

    public static Optional<JsonNode> parseStringOrFileToJsonNode(String hyperParameter) {
        if(Strings.isNullOrEmpty(hyperParameter)){
            return Optional.empty();
        }

        try{
            return Optional.of(OM.readTree(hyperParameter));
        } catch (JsonProcessingException e) {
            // Wasn't a JSON, maybe it was a file
            try {
                File file = new File(hyperParameter);
                return Optional.of(OM.readTree(file));
            } catch (IOException ex) {
                throw new MalformedAlgorithmException("Could not parse hyperparameter as JSON or file: " + hyperParameter, ex);
            }
        }
    }
}
