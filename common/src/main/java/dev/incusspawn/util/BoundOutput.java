package dev.incusspawn.util;

/**
 * The output bound when each message is reported ({@link BuildOutput#bound()}): for a caller
 * that hands shared code an output before it is known who owns the terminal by then, such as a
 * constant, or a CLI command that leaves the choice to whoever bound one.
 */
final class BoundOutput extends ForwardingOutput {

    static final BoundOutput INSTANCE = new BoundOutput();

    private BoundOutput() {}

    @Override
    Output delegate() {
        return BuildOutput.current();
    }
}
