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
 * A class loader over the current test classpath minus Micrometer Tracing: the {@code io.micrometer:micrometer-tracing*}
 * jars (the API and any tracer bridge) and Spring Boot's {@code spring-boot-micrometer-tracing*} auto-configuration jars.
 * Micrometer metrics and observation stay, as an application with Spring Boot Actuator but no tracing has them.
 * <p>
 * It exists to test what an application without micrometer-tracing - an optional dependency of the Essentials starters
 * - experiences. Spring Boot's {@code FilteredClassLoader} cannot show that: it only hides classes from lookups made
 * through it, while a configuration class loaded by the test class loader resolves the types in its method signatures
 * through the test class loader, which can see Micrometer Tracing. Classes loaded through this loader - its parent is
 * the platform class loader, not the test class loader - link against the reduced classpath, including the test's own
 * scenario classes and Spring itself.
 */
public final class WithoutMicrometerTracingClassLoader extends URLClassLoader {

    private WithoutMicrometerTracingClassLoader(URL[] urls) {
        super("without-micrometer-tracing", urls, ClassLoader.getPlatformClassLoader());
    }

    /**
     * @return a class loader over the current test classpath without the Micrometer Tracing jars
     */
    public static WithoutMicrometerTracingClassLoader fromTestClasspath() {
        // Surefire and Failsafe run tests through a manifest-only jar, so java.class.path does not list the real test classpath
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new WithoutMicrometerTracingClassLoader(Arrays.stream(classpath.split(File.pathSeparator))
                                                             .filter(entry -> !entry.isBlank() && !isMicrometerTracing(entry))
                                                             .map(WithoutMicrometerTracingClassLoader::toUrl)
                                                             .toArray(URL[]::new));
    }

    /**
     * Loads {@code scenarioClass} by name through this class loader and invokes its public static method {@code step},
     * which takes one {@code String} parameter per argument, with this class loader as the thread context class loader.
     *
     * @return what the method returned
     * @throws Throwable whatever the method threw, unwrapped - typically the {@code NoClassDefFoundError} under test
     */
    public Object runStatic(String scenarioClass, String step, String... arguments) throws Throwable {
        var scenario = Class.forName(scenarioClass, true, this);
        if (scenario.getClassLoader() != this) {
            throw new IllegalStateException(scenarioClass + " was not loaded in isolation, but by " + scenario.getClassLoader());
        }
        var parameterTypes = new Class<?>[arguments.length];
        Arrays.fill(parameterTypes, String.class);
        // Spring resolves classes and creates proxies through the thread context class loader, which must be this one too
        var thread                     = Thread.currentThread();
        var previousContextClassLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(this);
        try {
            return scenario.getMethod(step, parameterTypes).invoke(null, (Object[]) arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        } finally {
            thread.setContextClassLoader(previousContextClassLoader);
        }
    }

    /**
     * @return {@code true} if Micrometer Tracing's {@code Tracer} cannot be loaded through this class loader, i.e. the
     * isolation actually holds
     */
    public boolean hidesMicrometerTracing() {
        try {
            Class.forName("io.micrometer.tracing.Tracer", false, this);
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    private static boolean isMicrometerTracing(String classpathEntry) {
        var path = classpathEntry.replace('\\', '/');
        return path.contains("/io/micrometer/micrometer-tracing")
                || path.contains("/org/springframework/boot/spring-boot-micrometer-tracing");
    }

    private static URL toUrl(String classpathEntry) {
        try {
            return new File(classpathEntry).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(classpathEntry, e);
        }
    }
}
