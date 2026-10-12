package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqConverter;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
import io.quarkus.runtime.annotations.RegisterForReflection;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every DTO {@link Jjq} binds has a generated {@code _JqMapping} that Jjq registers (#1316). A
 * type without one falls back to jjq's reflective {@code ClassMapping}, which in the native image
 * only works for classes registered for reflection; with a mapping for each, no DTO needs
 * {@code @RegisterForReflection}. An enum still does, since jjq reads its wire names by reflection.
 * Walks the field types reachable from the root DTOs, so a new nested DTO that forgets
 * {@code @JqMapped}, a registry {@code Jjq} stops registering, or an enum left unregistered fails
 * here instead of in a native binary.
 */
class JjqGeneratedMappingTest {

    private static final List<Class<?>> ROOTS = List.of(
            ImageDef.class, ToolDef.class, SpawnConfig.class, ProjectConfig.class,
            BuildSource.class, McpConfig.class);

    private static final Set<Class<?>> REACHABLE = reachableTypes();

    @Test
    void everyReachableDtoHasARegisteredGeneratedMapping() throws Exception {
        var registered = registeredTypes();
        var missing = REACHABLE.stream()
                .filter(type -> !type.isEnum())
                .filter(dto -> !registered.contains(dto))
                .map(Class::getName)
                .toList();
        assertEquals(List.of(), missing, "DTOs bound by jjq without a generated mapping Jjq registers");
    }

    /**
     * jjq reads an enum's wire names ({@code @JqName} on its constants, {@code @JqEnum}) by
     * reflection, generated mappings or not; without registration the native image drops an
     * unknown-to-it value to null, so {@code type: api-key} silently stops reaching the account.
     */
    @Test
    void everyReachableEnumIsRegisteredForReflection() {
        var missing = REACHABLE.stream()
                .filter(Class::isEnum)
                // The constants' @JqName is read from their fields.
                .filter(e -> !e.isAnnotationPresent(RegisterForReflection.class)
                        || !e.getAnnotation(RegisterForReflection.class).fields())
                .map(Class::getName)
                .toList();
        assertEquals(List.of(), missing, "enums bound by jjq without @RegisterForReflection");
    }

    /**
     * The types {@link Jjq#buildMapper()} holds a generated mapping for. jjq has no public way to
     * ask, so this reads the mapper's cache, which {@code build()} fills with exactly the
     * registered mappings; a jjq upgrade that renames it fails here, loudly.
     */
    @SuppressWarnings("unchecked")
    private static Set<Class<?>> registeredTypes() throws ReflectiveOperationException {
        var cache = JqMapper.class.getDeclaredField("cache");
        cache.setAccessible(true);
        return Set.copyOf(((Map<Class<?>, ?>) cache.get(Jjq.buildMapper())).keySet());
    }

    private static Set<Class<?>> reachableTypes() {
        var seen = new LinkedHashSet<Class<?>>();
        var queue = new ArrayDeque<Class<?>>(ROOTS);
        while (!queue.isEmpty()) {
            var type = queue.poll();
            // jjq binds an enum by name, never through its fields.
            if (!seen.add(type) || type.isEnum()) continue;
            for (var c = type; c != null && c.getName().startsWith("dev.incusspawn."); c = c.getSuperclass()) {
                for (var field : c.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())) continue;
                    // A converter reads and writes the tree itself; JqIgnore never reaches the mapper.
                    if (field.isAnnotationPresent(JqConverter.class) || field.isAnnotationPresent(JqIgnore.class)) continue;
                    collect(field.getGenericType(), queue);
                }
            }
        }
        return seen;
    }

    private static void collect(Type type, ArrayDeque<Class<?>> queue) {
        if (type instanceof ParameterizedType p) {
            collect(p.getRawType(), queue);
            for (var arg : p.getActualTypeArguments()) collect(arg, queue);
        } else if (type instanceof GenericArrayType a) {
            collect(a.getGenericComponentType(), queue);
        } else if (type instanceof Class<?> c) {
            if (c.isArray()) {
                collect(c.getComponentType(), queue);
            } else if (c.getName().startsWith("dev.incusspawn.") && !c.isInterface()) {
                queue.add(c);
            }
        }
    }
}
