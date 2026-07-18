package com.hotvect.api.algodefinition;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.hotvect.utils.AlgorithmDefinitionReader;
import org.junit.jupiter.api.Test;

class RequiresLocalStateStorageTest {
    @Test
    void defaultsToFalse() throws Exception {
        assertFalse(definition("{}").requiresLocalStateStorage());
    }

    @Test
    void parsesTrue() throws Exception {
        assertTrue(definition("{\"requires_local_state_storage\":true}").requiresLocalStateStorage());
    }

    @Test
    void rejectsNonBooleanValuesWhileParsing() {
        assertThrows(
                IllegalArgumentException.class,
                () -> definition("{\"requires_local_state_storage\":\"true\"}"));
    }

    private static AlgorithmDefinition definition(String additionalFields) throws Exception {
        String suffix = additionalFields.equals("{}")
                ? ""
                : "," + additionalFields.substring(1, additionalFields.length() - 1);
        return new AlgorithmDefinitionReader().parse("""
                {"algorithm_name":"test","algorithm_version":"1","algorithm_factory_classname":"example.Factory"%s}
                """.formatted(suffix));
    }
}
