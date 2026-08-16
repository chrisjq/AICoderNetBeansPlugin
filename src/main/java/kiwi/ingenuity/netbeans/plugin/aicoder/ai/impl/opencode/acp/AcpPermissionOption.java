package kiwi.ingenuity.netbeans.plugin.aicoder.ai.impl.opencode.acp;

/**
 * A permission option offered during request/permission flow. OpenCode always
 * offers exactly three options — once/allow_once, always/allow_always,
 * reject/reject_once. Any optionId other than once or always is treated by
 * OpenCode as a rejection.
 */
public class AcpPermissionOption {

    private String optionId;
    private String name;
    private AcpPermissionKindEnum kind;

    public AcpPermissionOption(String optionId, String name, AcpPermissionKindEnum kind) {
        this.optionId = optionId;
        this.name = name;
        this.kind = kind;
    }

    public String optionId() {
        return optionId;
    }

    public void setOptionId(String optionId) {
        this.optionId = optionId;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AcpPermissionKindEnum kind() {
        return kind;
    }

    public void setKind(AcpPermissionKindEnum kind) {
        this.kind = kind;
    }

    @Override
    public String toString() {
        return "AcpPermissionOption{" + "optionId=" + optionId + ", name=" + name
                + ", kind=" + kind + '}';
    }
}
