package dev.incusspawn.lifecycle;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.function.BooleanSupplier;

/**
 * Makes {@link BranchFlow} branch as on Linux for each test, wherever the test runs: on macOS it
 * refuses VM sources and turns KVM off (#1254), which a test of VM or KVM branching does not expect.
 */
public final class LinuxBranching implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NS = ExtensionContext.Namespace.create(LinuxBranching.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NS).put("macOS", BranchFlow.macOS);
        BranchFlow.macOS = () -> false;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        BranchFlow.macOS = context.getStore(NS).get("macOS", BooleanSupplier.class);
    }
}
