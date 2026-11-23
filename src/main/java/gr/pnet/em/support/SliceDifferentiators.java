package gr.pnet.em.support;

import java.util.Locale;
import java.util.Objects;

/**
 * Comparing slice differentiators (SDs). 3GPP reserves the SD ffffff for "no SD" (TS 23.003), and Open5GS reports a
 * slice without an SD with it, while its subscription data and TEP's resources leave the SD out.
 */
public final class SliceDifferentiators {

    private static final String NO_SD = "ffffff";

    private SliceDifferentiators() {
    }

    /** Whether two SDs are the same: a missing SD and ffffff both mean none, and hex digits match in either case. */
    public static boolean same(String sd, String other) {
        return Objects.equals(comparable(sd), comparable(other));
    }

    /** The SD as TEP reports it: null for none, whether missing or ffffff, and otherwise as given. */
    public static String present(String sd) {
        return comparable(sd) == null ? null : sd;
    }

    private static String comparable(String sd) {
        return sd == null || sd.isEmpty() || sd.equalsIgnoreCase(NO_SD) ? null : sd.toLowerCase(Locale.ROOT);
    }
}
