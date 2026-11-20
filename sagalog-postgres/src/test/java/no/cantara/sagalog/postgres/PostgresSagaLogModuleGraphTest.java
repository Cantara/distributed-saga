package no.cantara.sagalog.postgres;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.lang.module.ModuleDescriptor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.testng.Assert.assertTrue;

/**
 * jlink accepts only explicit modules: every module that this module requires, directly or through them, must have a
 * {@code module-info}. An automatic module (a jar with only an {@code Automatic-Module-Name}) breaks jlink. The JDBC
 * driver is the application's, and is found at run time; it is not required here.
 */
public class PostgresSagaLogModuleGraphTest {

    @Test
    public void thatNoRequiredModuleIsAutomatic() {
        Module module = PostgresSagaLogInitializer.class.getModule();
        if (!module.isNamed()) {
            throw new SkipException("the tests run on the class path");
        }
        ModuleLayer layer = module.getLayer();
        List<String> automatic = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<ModuleDescriptor> todo = new ArrayDeque<>();
        todo.add(module.getDescriptor());
        while (!todo.isEmpty()) {
            ModuleDescriptor descriptor = todo.poll();
            if (!seen.add(descriptor.name())) {
                continue;
            }
            if (descriptor.isAutomatic()) {
                automatic.add(descriptor.name());
                continue;
            }
            for (ModuleDescriptor.Requires requires : descriptor.requires()) {
                if (requires.modifiers().contains(ModuleDescriptor.Requires.Modifier.STATIC)) {
                    continue; // optional at run time, and jlink leaves it out
                }
                Optional<Module> required = layer.findModule(requires.name());
                assertTrue(required.isPresent(), descriptor.name() + " requires " + requires.name() + ", which is not resolved");
                todo.add(required.get().getDescriptor());
            }
        }
        assertTrue(automatic.isEmpty(), "automatic modules in the graph of " + module.getName() + ", which jlink rejects: " + automatic);
    }
}
