package com.eshioji.hotvect.core.audit;

import com.eshioji.hotvect.api.codec.ExampleEncoder;
import com.eshioji.hotvect.core.vectorization.DefaultVectorizer;

public interface AuditableExampleEncoder<R> extends ExampleEncoder<R> {
    AuditableVectorizer<R> getVectorizer();
}
