/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.kroxylicious.krpccodegen.model;

import io.kroxylicious.krpccodegen.schema.StructSpec;

import freemarker.template.AdapterTemplateModel;
import freemarker.template.TemplateHashModel;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateModelException;

class StructSpecModel implements TemplateHashModel, AdapterTemplateModel {
    final StructSpec spec;
    final KrpcSchemaObjectWrapper wrapper;

    StructSpecModel(KrpcSchemaObjectWrapper wrapper, StructSpec ms) {
        this.wrapper = wrapper;
        this.spec = ms;
    }

    @Override
    public TemplateModel get(String key) throws TemplateModelException {
        switch (key) {
            case "name":
                return wrapper.wrap(spec.name());
            case "fields":
                return wrapper.wrap(spec.fields());
            case "versions":
                return wrapper.wrap(spec.versions());
            case "versionsString":
                return wrapper.wrap(spec.versionsString());
            case "hasKeys":
                return wrapper.wrap(spec.hasKeys());
        }
        throw new TemplateModelException(spec.getClass().getSimpleName() + " doesn't have property " + key);
    }

    @Override
    public boolean isEmpty() throws TemplateModelException {
        return false;
    }

    @Override
    public Object getAdaptedObject(Class<?> hint) {
        return spec;
    }
}
