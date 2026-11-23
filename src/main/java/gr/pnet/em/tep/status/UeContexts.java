package gr.pnet.em.tep.status;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The AMF's ue-info answer: one context per registered UE. Only what TEP uses is mapped: the UE's SUPI, its
 * registration and connection states, and its location.
 */
public record UeContexts(List<UeContext> items) {

    /** mm_state is registered or deregistered, cm_state connected or idle. */
    public record UeContext(String supi, @JsonProperty("mm_state") String mmState,
            @JsonProperty("cm_state") String cmState, Location location) {
    }

    /** Where the UE last reported from, as of timestamp (microseconds since the epoch). */
    public record Location(Long timestamp, @JsonProperty("nr_tai") NrTai nrTai, @JsonProperty("nr_cgi") NrCgi nrCgi,
            @JsonProperty("last_visited_plmn_id") String lastVisitedPlmnId) {
    }

    /** Tracking area: PLMN as MCC followed by MNC (e.g. 99970), and the TAC, also as six hex digits. */
    public record NrTai(String plmn, @JsonProperty("tac_hex") String tacHex, Integer tac) {
    }

    /** NR cell: PLMN as in the tracking area, and the 36-bit NCI, which is the gNB id followed by the cell id. */
    public record NrCgi(String plmn, Long nci, @JsonProperty("gnb_id") Long gnbId,
            @JsonProperty("cell_id") Integer cellId) {
    }
}
