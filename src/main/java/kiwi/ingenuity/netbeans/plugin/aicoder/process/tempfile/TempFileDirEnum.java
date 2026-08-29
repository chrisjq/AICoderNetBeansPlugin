package kiwi.ingenuity.netbeans.plugin.aicoder.process.tempfile;

/**
 * Recognised subdirectories inside each session's registry-owned temp tree ({@code tmp}) where spooled tool output is
 * parked. Passing one of these (rather than an arbitrary string) keeps the directories that matter — e.g.
 * {@code tool_results} for build/test and git output — discoverable and defined in one place.
 */
public enum TempFileDirEnum {

    /**
     * Where build/test results and large git read output are parked — kept distinct so results stay recognisable next
     * to other temp content such as pasted images.
     */
    TOOL_RESULTS("tool_results"),
    /**
     * Where images pasted into the prompt are written. Previously these landed in the {@code tmp} root, which left the
     * tree undescriptive — a directory of loose {@code .png} files beside whatever else happened to be there.
     *
     * <p>
     * Moving them matters to {@code TmpMarkerExpander}: the {@code @tmp.<filename>} marker carries no directory, so the
     * expander searches the root and then every directory named here. Adding a constant to this enum therefore makes
     * that directory's files addressable by marker automatically — which is why the search is over {@link #values()}
     * rather than a hardcoded list.</p>
     */
    PASTED_IMAGES("pasted_images");

    private final String dirName;

    TempFileDirEnum(String dirName) {
        this.dirName = dirName;
    }

    /**
     * The subdirectory name under the session's {@code tmp} root (e.g. {@code "tool_results"}).
     */
    public String dirName() {
        return dirName;
    }
}
