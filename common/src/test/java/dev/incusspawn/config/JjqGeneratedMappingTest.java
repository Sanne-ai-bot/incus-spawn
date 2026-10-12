package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqConverter;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import io.hyperfoil.tools.jjq.mapper.JqMapper;
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
 * only works for classes registered for reflection; with a mapping for each, none needs
 * {@code @RegisterForReflection}. Walks the field types reachable from the root DTOs, so a new
 * nested DTO that forgets {@code @JqMapped}, or a registry {@code Jjq} stops registering, fails
 * here instead of in a native binary.
 */
class JjqGeneratedMappingTest {

    private static final List<Class<?>> ROOTS = List.of(
            ImageDef.class, ToolDef.class, SpawnConfig.class, ProjectConfig.class,
            BuildSource.class, McpConfig.class);

    @Test
    void everyReachableDtoHasARegisteredGeneratedMapping() throws Exception {
        var registered = registeredTypes();
        var missing = reachableDtos().stream()
                .filter(dto -> !registered.contains(dto))
                .map(Class::getName)
                .toList();
        assertEquals(List.of(), missing, "DTOs bound by jjq without a generated mapping Jjq registers");
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

    private static Set<Class<?>> reachableDtos() {
        var seen = new LinkedHashSet<Class<?>>();
        var queue = new ArrayDeque<Class<?>>(ROOTS);
        while (!queue.isEmpty()) {
            var type = queue.poll();
            if (!seen.add(type)) continue;
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
            } else if (c.getName().startsWith("dev.incusspawn.") && !c.isEnum() && !c.isInterface()) {
                queue.add(c);
            }
        }
    }
}
