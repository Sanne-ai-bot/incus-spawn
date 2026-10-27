package dev.incusspawn.lifecycle;

/**
 * Makes {@link BranchFlow} branch as on macOS for each test, wherever the test runs, so a test can
 * see what it refuses there (VM sources and KVM, #1254). On a method it overrides a class's
 * {@link LinuxBranching}.
 */
public final class MacOsBranching extends BranchingPlatform {

    public MacOsBranching() {
        super(true);
    }
}
