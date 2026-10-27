package dev.incusspawn.lifecycle;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.function.BooleanSupplier;

/** Makes {@link BranchFlow} branch as on the platform a subclass names, for each test, restoring it after. */
abstract class BranchingPlatform implements BeforeEachCallback, AfterEachCallback {

    private final boolean macOS;

    BranchingPlatform(boolean macOS) {
        this.macOS = macOS;
    }

    private ExtensionContext.Store store(ExtensionContext context) {
        return context.getStore(ExtensionContext.Namespace.create(getClass()));
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        store(context).put("macOS", BranchFlow.macOS);
        BranchFlow.macOS = () -> macOS;
    }

    @Override
    public void afterEach(ExtensionContext context) {
        BranchFlow.macOS = store(context).get("macOS", BooleanSupplier.class);
    }
}
