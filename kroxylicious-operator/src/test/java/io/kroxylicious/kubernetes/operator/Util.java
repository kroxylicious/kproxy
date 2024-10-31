/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.kubernetes.operator;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;

import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import io.kroxylicious.kubernetes.api.v1alpha1.KafkaProxy;

/**
 * Constants and methods used by multiple other classes.
 */
public class Util {

    static final YAMLMapper YAML_MAPPER = new YAMLMapper()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);

    private Util() {
    }

    public static KafkaProxy kafkaProxyFromResource(String name) {
        // TODO should validate against the CRD schema, because the DependentResource
        // should never see an invalid resource in production
        try {
            return YAML_MAPPER.readValue(Util.class.getResource(name), KafkaProxy.class);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static KafkaProxy kafkaProxyFromFile(Path path) {
        // TODO should validate against the CRD schema, because the DependentResource
        // should never see an invalid resource in production
        try {
            return YAML_MAPPER.readValue(path.toFile(), KafkaProxy.class);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
