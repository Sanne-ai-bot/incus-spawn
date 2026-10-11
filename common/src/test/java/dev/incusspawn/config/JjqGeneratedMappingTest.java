package dev.incusspawn.config;

import dev.incusspawn.tool.ToolDef;
import io.hyperfoil.tools.jjq.mapper.JqConverter;
import io.hyperfoil.tools.jjq.mapper.JqIgnore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Every DTO {@link Jjq} binds has a generated {@code _JqMapping} (#1316). A type without one
 * falls back to jjq's reflective {@code ClassMapping}, which in the native image only works for
 * classes registered for reflection; with a mapping for each, none needs
 * {@code @RegisterForReflection}. Walks the field types reachable from the root DTOs, so a new
 * nested DTO that forgets {@code @JqMapped} fails here instead of in a native binary.
 */
class JjqGeneratedMappingTest {

    private static final List<Class<?>> ROOTS = List.of(
            ImageDef.class, ToolDef.class, SpawnConfig.class, ProjectConfig.class,
            BuildSource.class, McpConfig.class);

    /** The packages whose generated {@code JqMappingRegistry} {@link Jjq} registers. */
    private static final Set<String> REGISTERED_PACKAGES = Set.of("dev.incusspawn.config", "dev.incusspawn.tool");

    @Test
    void everyReachableDtoHasAGeneratedMapping() {
        var missing = new ArrayList<String>();
        for (var dto : reachableDtos()) {
            try {
                Class.forName(dto.getName().replace('$', '_') + "_JqMapping", false, dto.getClassLoader());
                if (!REGISTERED_PACKAGES.contains(dto.getPackageName())) missing.add(dto.getName() + " (registry not in Jjq)");
            } catch (ClassNotFoundException e) {
                missing.add(dto.getName());
            }
        }
        assertEquals(List.of(), missing, "DTOs bound by jjq without @JqMapped");
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
