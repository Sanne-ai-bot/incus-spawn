package dev.incusspawn.config;

import io.hyperfoil.tools.jjq.mapper.JqMapperException;
import io.hyperfoil.tools.jjq.mapper.ValueConverter;
import io.hyperfoil.tools.jjq.value.JqArray;
import io.hyperfoil.tools.jjq.value.JqNull;
import io.hyperfoil.tools.jjq.value.JqString;
import io.hyperfoil.tools.jjq.value.JqValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Shell commands written as one string or as a list of them, such as a repo's {@code prime} (#1745).
 * Absent, empty and blank items declare nothing; a mapping or a nested list fails the file.
 * One command is written back as a string, so a definition keeps the shape it was read in.
 */
public class CommandListConverter implements ValueConverter<List<String>> {

    @Override
    public List<String> fromJqValue(JqValue value) {
        if (value == null || value.isNull()) return List.of();
        var commands = new ArrayList<String>();
        for (var item : value.isArray() ? value.asList() : List.of(value)) {
            if (item.isNull()) continue;
            if (item.isContainer()) throw new JqMapperException("expects a command or a list of commands");
            var command = Jjq.text(item);
            if (!command.isBlank()) commands.add(command);
        }
        return List.copyOf(commands);
    }

    @Override
    public JqValue toJqValue(List<String> value) {
        if (value == null || value.isEmpty()) return JqNull.NULL;
        if (value.size() == 1) return JqString.of(value.getFirst());
        var array = JqArray.arrayBuilder();
        for (var command : value) array.add(command);
        return array.build();
    }
}
