package com.hotvect.core.transform.ranking;

import com.google.common.collect.ImmutableMap;
import com.hotvect.api.data.Namespace;
import com.hotvect.api.transformation.memoization.ComputingCandidate;
import com.hotvect.api.transformation.memoization.InteractingComputation;
import net.jodah.typetools.TypeResolver;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

public enum TestInteractionAttr implements Namespace {
    interact1(TestInteractionAttr::interact1String),
    interact2(TestInteractionAttr::interact1StringLength),
    interact3(x -> ImmutableMap.of(x.getShared().getOriginalInput(), x.getAction().getOriginalInput())),
    interact4(TestInteractionAttr::interact4Map);

    private final InteractingComputation<String, String, ?> interactingComputation;

    TestInteractionAttr(InteractingComputation<String, String, ?> interactingComputation) {
        this.interactingComputation = interactingComputation;
    }

    public <V> InteractingComputation<String, String, V> get() {
        return (InteractingComputation<String, String, V>) this.interactingComputation;
    }

    private static int interact1StringLength(ComputingCandidate<String, String> computingCandidate) {
        String sharedOriginalInput = computingCandidate.getShared().getOriginalInput();
        String actionOriginalInput = computingCandidate.getAction().getOriginalInput();
        return sharedOriginalInput.length() + actionOriginalInput.length();
    }

    private static String interact1String(ComputingCandidate<String, String> computingCandidate) {
        return computingCandidate.getShared().getOriginalInput() + computingCandidate.getAction().getOriginalInput();
    }

    private static Map<String, String> interact4Map(ComputingCandidate<String, String> computingCandidate) {
        return ImmutableMap.of(computingCandidate.getShared().getOriginalInput(), computingCandidate.getAction().getOriginalInput());
    }

    public static void main(String[] args) {
        for (TestInteractionAttr attr : TestInteractionAttr.values()) {
            InteractingComputation<String, String, ?> transformation = attr.get();
            Type[] types = TypeResolver.resolveRawArguments(InteractingComputation.class, transformation.getClass());
            if (types != null && types.length == 3) {
                System.out.println("Method name: " + attr.name());
                System.out.println("Return type: " + types[2].getTypeName());
                printTypeDetails(types[2], 1);
            } else {
                System.out.println("Could not resolve types for: " + attr.name());
            }
        }
    }

    private static void printTypeDetails(Type type, int depth) {
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            System.out.println(getIndent(depth) + "Raw type: " + parameterizedType.getRawType().getTypeName());
            for (Type arg : parameterizedType.getActualTypeArguments()) {
                System.out.println(getIndent(depth) + "Type argument: " + arg.getTypeName());
                printTypeDetails(arg, depth + 1);
            }
        }
    }

    private static String getIndent(int depth) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            indent.append("  ");
        }
        return indent.toString();
    }
}
