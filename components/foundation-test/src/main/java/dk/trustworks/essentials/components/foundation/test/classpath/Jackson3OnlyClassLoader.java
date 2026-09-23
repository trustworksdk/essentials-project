/*
 * Copyright 2021-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.trustworks.essentials.components.foundation.test.classpath;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.util.Arrays;

/**
 * A class loader over the current test classpath minus the Jackson 2 runtime jars (databind, core, datatype, module and
 * dataformat artifacts under {@code com.fasterxml.jackson}); {@code jackson-annotations} stays, as Jackson 3 uses it too.
 * <p>
 * It exists to test what an application whose only Jackson is Jackson 3 experiences. A plain unit test cannot show
 * that: the Essentials test classpaths carry both Jackson majors, so a class that needs Jackson 2 links fine there and
 * fails with {@code NoClassDefFoundError} only in the application. Classes loaded through this loader - its parent is
 * the platform class loader, not the test class loader - link against the reduced classpath, including the test's own
 * scenario classes.
 */
public final class Jackson3OnlyClassLoader extends URLClassLoader {

    private Jackson3OnlyClassLoader(URL[] urls) {
        super("jackson3-only", urls, ClassLoader.getPlatformClassLoader());
    }

    /**
     * @return a class loader over the current test classpath without the Jackson 2 runtime jars
     */
    public static Jackson3OnlyClassLoader fromTestClasspath() {
        // Surefire runs tests through a manifest-only jar, so java.class.path does not list the real test classpath
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new Jackson3OnlyClassLoader(Arrays.stream(classpath.split(File.pathSeparator))
                                                 .filter(entry -> !entry.isBlank() && !isJackson2Runtime(entry))
                                                 .map(Jackson3OnlyClassLoader::toUrl)
                                                 .toArray(URL[]::new));
    }

    /**
     * Loads {@code scenarioClass} by name through this class loader and invokes its public static no-argument method
     * {@code step}.
     *
     * @return what the method returned
     * @throws Throwable whatever the method threw, unwrapped - typically the {@code NoClassDefFoundError} under test
     */
    public Object runStatic(String scenarioClass, String step) throws Throwable {
        var scenario = Class.forName(scenarioClass, true, this);
        if (scenario.getClassLoader() != this) {
            throw new IllegalStateException(scenarioClass + " was not loaded in isolation, but by " + scenario.getClassLoader());
        }
        try {
            return scenario.getMethod(step).invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    /**
     * @return {@code true} if Jackson 2's {@code ObjectMapper} cannot be loaded through this class loader, i.e. the
     * isolation actually holds
     */
    public boolean hidesJackson2() {
        try {
            Class.forName("com.fasterxml.jackson.databind.ObjectMapper", false, this);
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    private static boolean isJackson2Runtime(String classpathEntry) {
        var path = classpathEntry.replace('\\', '/');
        return path.contains("/com/fasterxml/jackson/core/jackson-databind/")
                || path.contains("/com/fasterxml/jackson/core/jackson-core/")
                || path.contains("/com/fasterxml/jackson/datatype/")
                || path.contains("/com/fasterxml/jackson/module/")
                || path.contains("/com/fasterxml/jackson/dataformat/");
    }

    private static URL toUrl(String classpathEntry) {
        try {
            return new File(classpathEntry).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(classpathEntry, e);
        }
    }
}
