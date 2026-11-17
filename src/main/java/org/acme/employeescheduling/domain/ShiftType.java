package org.acme.employeescheduling.domain;

/**
 * The two shifts of a day. The code is PLR_SM.SM (and later PLR_SNAR.SM); the times come from PLR_SM
 * ({@link ShiftTime}), not from this enum. Crew shift ids use the name (DAY, NIGHT), not the code.
 */
public enum ShiftType {
    DAY("1"),
    NIGHT("2");

    private final String code;

    ShiftType(String code) {
        this.code = code;
    }

    /** PLR_SM.SM: "1" = DAY, "2" = NIGHT. */
    public String getCode() {
        return code;
    }

    /** The type of a PLR_SM.SM value (CHAR column, so it is trimmed). */
    public static ShiftType fromCode(String code) {
        String trimmed = code == null ? "" : code.trim();
        for (ShiftType type : values()) {
            if (type.code.equals(trimmed)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown shift type code (PLR_SM.SM): '" + code + "'.");
    }
}
