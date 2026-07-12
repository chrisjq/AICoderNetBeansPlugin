package kiwi.ingenuity.netbeans.plugin.aicoder;

/**
 * Sub-options gated behind the "Allow Database Access" master toggle —
 * mirrors {@link WebRequestAccessOptionEnum}'s master + per-verb structure.
 * READ_ONLY is not independently disableable via the UI (this plugin has no
 * write-capable database tools), but is modeled as a real setting so the
 * enforcement point stays honest about what it's actually checking rather
 * than being hardcoded.
 */
public enum DatabaseAccessOptionEnum {
    READ_ONLY(AccessControlLabelEnum.ALLOW_DATABASE_READ_ONLY),
    SCHEMA(AccessControlLabelEnum.ALLOW_DATABASE_SCHEMA),
    SELECT(AccessControlLabelEnum.ALLOW_DATABASE_SELECT),
    EXECUTE_SQL(AccessControlLabelEnum.ALLOW_DATABASE_EXECUTE_SQL);

    private final AccessControlLabelEnum label;

    DatabaseAccessOptionEnum(AccessControlLabelEnum label) {
        this.label = label;
    }

    public AccessControlLabelEnum label() {
        return label;
    }
}
