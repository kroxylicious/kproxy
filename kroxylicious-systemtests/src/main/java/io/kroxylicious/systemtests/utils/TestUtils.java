/*
 * Copyright Kroxylicious Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.kroxylicious.systemtests.utils;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * The type Test utils.
 */
public class TestUtils {

    private TestUtils() {
    }

    /**
     * Gets default posix file permissions.
     *
     * @return the default posix file permissions
     */
    public static FileAttribute<Set<PosixFilePermission>> getDefaultPosixFilePermissions() {
        return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));
    }

    /**
     * Gets resources URI.
     *
     * @param fileName the file name
     * @return the resources URI
     */
    @NonNull
    public static URI getResourcesURI(String fileName) {
        URI overrideFile;
        var resource = TestUtils.class.getClassLoader().getResource(fileName);
        try {
            if (resource == null) {
                throw new IllegalArgumentException("Cannot find resource " + fileName + " on classpath");
            }
            overrideFile = resource.toURI();
        }
        catch (URISyntaxException e) {
            throw new IllegalStateException("Cannot determine file system path for " + resource, e);
        }
        return overrideFile;
    }
}
