package com.acme.edge.views.java_edge;

import com.acme.edge.events.Pinged;
import com.acme.edge.events.Ponged;
import dk.trustworks.essentials.components.foundation.messaging.MessageHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/* A block comment holding a quote " and a brace { and @MessageHandler void on(Bogus b) {} */
@RestController
@SuppressWarnings({"unchecked", "rawtypes"})
public class JavaEdge {
    private static final String BLOCK = """
            @MessageHandler void on(Bogus b) { "}" }
            @GetMapping("/phantom")
            \"""still inside\"""
            """;
    private static final char OPEN = '{';
    private static final char QUOTE = '"';
    private static final char TICK = '\'';
    private static final String UNICODE = "{ not a brace";
    private final Map<String, List<Map<String, Integer>>> nested = Map.of();
    private final Runnable lambda = () -> {
        new Object() {
            @MessageHandler
            void on(Pinged inAnonymousClass) {
            }
        };
    };
    private final Class<?> type = JavaEdge.class;

    static {
        String s = "static { initializer }";
    }

    public <T extends Comparable<? super T>> void sort(List<T> items) {
        items.sort(null);
    }

    @GetMapping("/edge")
    public List<String> edge() {
        return List.of("}", "{", "@GetMapping(\"/phantom2\")");
    }

    /** RULE: a handler on a static nested class belongs to that class. */
    public static class Inner {
        @MessageHandler
        void on(Ponged event) {
        }
    }

    enum Mode {
        A {
            @Override
            String label() {
                return "a{";
            }
        },
        B;

        String label() {
            return "b";
        }
    }

    public sealed interface Shape permits Circle, Square {
    }

    public record Circle(double r) implements Shape {
        public Circle {
            if (r < 0) {
                throw new IllegalArgumentException("r < 0");
            }
        }
    }

    public non-sealed static class Square implements Shape {
    }

    public @interface Marker {
        String value() default "}";
    }
}
