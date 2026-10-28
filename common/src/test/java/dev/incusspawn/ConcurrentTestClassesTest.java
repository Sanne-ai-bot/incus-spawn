package dev.incusspawn;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * common's surefire configuration enables JUnit's parallel execution, and a test class marked
 * {@code @Execution(CONCURRENT)} runs beside every other class in the module, not only its own
 * methods beside each other. Its processes then inherit other tests' files while they are open for
 * writing: a stub script written and run straight away fails its exec with ETXTBSY, and dash then
 * runs the host's command of the same name instead (#1846). Methods opt in; classes never do.
 */
class ConcurrentTestClassesTest {

    @Test
    void noTestClassRunsBesideTheOthers() throws Exception {
        var root = Path.of(ConcurrentTestClassesTest.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        var loader = ConcurrentTestClassesTest.class.getClassLoader();
        try (var classes = Files.walk(root)) {
            var offenders = classes.map(p -> root.relativize(p).toString())
                    .filter(name -> name.endsWith(".class") && !name.contains("$"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .filter(name -> {
                        try {
                            var execution = Class.forName(name, false, loader).getAnnotation(Execution.class);
                            return execution != null && execution.value() == ExecutionMode.CONCURRENT;
                        } catch (ClassNotFoundException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .sorted().toList();
            assertEquals(List.of(), offenders, "mark the methods @Execution(CONCURRENT), not the class");
        }
    }
}
