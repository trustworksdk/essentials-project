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
import java.util.*;

import static dk.trustworks.essentials.shared.FailFast.requireNonNull;

/**
 * A class loader over the current test classpath minus a given set of jars, matched by Maven repository path prefix
 * ({@code group/path/artifactId-prefix}, e.g. {@code io/micrometer/micrometer-tracing}).
 * <p>
 * It exists to test what an application without one of the Essentials starters' optional ({@code provided} or
 * {@code optional}) dependencies experiences. Spring Boot's {@code FilteredClassLoader} cannot show that: it only hides
 * classes from lookups made through it, while a configuration class loaded by the test class loader resolves the types
 * in its method signatures through the test class loader, which can see them. Classes loaded through this loader - its
 * parent is the platform class loader, not the test class loader - link against the reduced classpath, including the
 * test's own scenario classes and Spring itself.
 * <p>
 * The factory methods cover the classpaths the starters support; {@link #fromTestClasspathWithout(String, Collection)}
 * builds any other.
 */
public final class WithoutJarsClassLoader extends URLClassLoader {
    /**
     * Micrometer Tracing: the {@code io.micrometer:micrometer-tracing*} jars (the API and any tracer bridge) and Spring
     * Boot's {@code spring-boot-micrometer-tracing*} auto-configuration jars.
     */
    public static final List<String> MICROMETER_TRACING_JARS = List.of("io/micrometer/micrometer-tracing",
                                                                       "org/springframework/boot/spring-boot-micrometer-tracing");

    /**
     * Micrometer metrics and tracing: micrometer-core and everything built on it (registries, binders, test support),
     * Micrometer Tracing, and Spring Boot's {@code spring-boot-micrometer-*} auto-configuration jars. micrometer-observation
     * and micrometer-commons are not in it: spring-context depends on them, so every Spring application has them.
     */
    public static final List<String> MICROMETER_JARS = List.of("io/micrometer/micrometer-core",
                                                               "io/micrometer/micrometer-registry-",
                                                               "io/micrometer/micrometer-java",
                                                               "io/micrometer/micrometer-jakarta",
                                                               "io/micrometer/micrometer-jetty",
                                                               "io/micrometer/micrometer-test",
                                                               "io/micrometer/micrometer-tracing",
                                                               "org/springframework/boot/spring-boot-micrometer-");

    /**
     * Spring Boot's health contributor API: the {@code org.springframework.boot:spring-boot-health} jar, which Spring
     * Boot Actuator brings.
     */
    public static final List<String> SPRING_BOOT_HEALTH_JARS = List.of("org/springframework/boot/spring-boot-health/");

    private WithoutJarsClassLoader(String name, URL[] urls) {
        super(name, urls, ClassLoader.getPlatformClassLoader());
    }

    /**
     * @return a class loader over the current test classpath without the {@link #MICROMETER_TRACING_JARS}; Micrometer
     * metrics and observation stay, as an application with Spring Boot Actuator but no tracer has them
     */
    public static WithoutJarsClassLoader withoutMicrometerTracing() {
        return fromTestClasspathWithout("without-micrometer-tracing", MICROMETER_TRACING_JARS);
    }

    /**
     * @return a class loader over the current test classpath without the {@link #MICROMETER_JARS}, as an application
     * without Spring Boot Actuator or any Micrometer dependency of its own has it
     */
    public static WithoutJarsClassLoader withoutMicrometer() {
        return fromTestClasspathWithout("without-micrometer", MICROMETER_JARS);
    }

    /**
     * @return a class loader over the current test classpath without the {@link #SPRING_BOOT_HEALTH_JARS}; Micrometer
     * stays, as an application that adds Micrometer metrics without Spring Boot Actuator has it
     */
    public static WithoutJarsClassLoader withoutSpringBootHealth() {
        return fromTestClasspathWithout("without-spring-boot-health", SPRING_BOOT_HEALTH_JARS);
    }

    /**
     * @param name            the class loader's name, shown in stack traces
     * @param excludedJarPaths Maven repository path prefixes ({@code group/path/artifactId-prefix}) of the jars to leave
     *                        out; an empty collection leaves the full test classpath, which makes a baseline for a
     *                        scenario run the same way
     * @return a class loader over the current test classpath without the jars whose path contains one of
     * {@code excludedJarPaths}
     */
    public static WithoutJarsClassLoader fromTestClasspathWithout(String name, Collection<String> excludedJarPaths) {
        requireNonNull(name, "No name provided");
        var excluded = List.copyOf(requireNonNull(excludedJarPaths, "No excludedJarPaths provided"));
        // Surefire and Failsafe run tests through a manifest-only jar, so java.class.path does not list the real test classpath
        var classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new WithoutJarsClassLoader(name,
                                          Arrays.stream(classpath.split(File.pathSeparator))
                                                .filter(entry -> !entry.isBlank() && !isExcluded(entry, excluded))
                                                .map(WithoutJarsClassLoader::toUrl)
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
     * @param className the fully qualified name of a class from one of the excluded jars
     * @return {@code true} if {@code className} cannot be loaded through this class loader, i.e. the isolation actually
     * holds
     */
    public boolean hides(String className) {
        try {
            Class.forName(className, false, this);
            return false;
        } catch (ClassNotFoundException e) {
            return true;
        }
    }

    private static boolean isExcluded(String classpathEntry, List<String> excludedJarPaths) {
        var path = classpathEntry.replace('\\', '/');
        return excludedJarPaths.stream().anyMatch(excluded -> path.contains("/" + excluded));
    }

    private static URL toUrl(String classpathEntry) {
        try {
            return new File(classpathEntry).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(classpathEntry, e);
        }
    }
}
