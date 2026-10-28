package dev.incusspawn.graal;

import org.graalvm.nativeimage.hosted.Feature;

/**
 * Build-time guard that fails the native-image build unless the image takes the platform role of
 * the OS it is built for: {@link dev.incusspawn.Platform#isMacOS()} only in a macOS image,
 * {@link dev.incusspawn.Platform#isLinux()} only in a Linux one.
 *
 * <p>The role decides what the proxy trusts. On macOS every container reaches it from the VM's
 * address, so a credential proof identifies a caller without its address; on Linux the address
 * check stays (#1582, {@code MitmProxy.proofMayIdentify}). {@code Platform} is initialized when
 * the image is built and its answers are folded into the binary, so nothing at run time can change
 * them. This guard covers what could at build time: an {@code os.name} passed to the builder, or a
 * {@code Platform} that is not the one compiled from {@code common}'s sources, such as the
 * macOS-role test build's (#1800, {@code scripts/macos-role-build.sh}). Either way a shipped binary
 * would be in the wrong role, and this build stops instead.
 */
public class PlatformRoleFeature implements Feature {

    @Override
    public String getDescription() {
        return "Fails the build unless the image's platform role is its target OS's";
    }

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        var problem = mismatch(
                org.graalvm.nativeimage.Platform.includedIn(org.graalvm.nativeimage.Platform.DARWIN.class),
                org.graalvm.nativeimage.Platform.includedIn(org.graalvm.nativeimage.Platform.LINUX.class),
                dev.incusspawn.Platform.isMacOS(), dev.incusspawn.Platform.isLinux());
        if (problem != null) {
            throw new Error("[isx-platform-guard] Build aborted: " + problem
                    + ". A binary in another OS's role would apply that OS's caller checks.");
        }
        System.err.println("[isx-platform-guard] platform role matches the target OS");
    }

    /** Why the role does not fit the target, or null when it does. */
    static String mismatch(boolean targetMacOS, boolean targetLinux, boolean macOS, boolean linux) {
        if (macOS != targetMacOS) {
            return "Platform.isMacOS() is " + macOS + " for a " + (targetMacOS ? "macOS" : "non-macOS") + " target";
        }
        if (linux != targetLinux) {
            return "Platform.isLinux() is " + linux + " for a " + (targetLinux ? "Linux" : "non-Linux") + " target";
        }
        return null;
    }
}
