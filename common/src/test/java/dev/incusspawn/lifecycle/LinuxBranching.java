package dev.incusspawn.lifecycle;

/**
 * Makes {@link BranchFlow} branch as on Linux for each test, wherever the test runs: on macOS it
 * refuses VM sources and turns KVM off (#1254), which a test of VM or KVM branching does not expect.
 */
public final class LinuxBranching extends BranchingPlatform {

    public LinuxBranching() {
        super(false);
    }
}
