package gr.pnet.em.tep.status;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/** The SMF's pdu-info answer: the PDU sessions of each UE that has any. Only what TEP reports is mapped. */
public record UeSessions(List<UeSession> items) {

    public record UeSession(String supi, List<Pdu> pdu) {
    }

    public record Pdu(Integer psi, String dnn, String ipv4, Snssai snssai,
            @JsonProperty("qos_flows") List<QosFlow> qosFlows) {
    }

    public record Snssai(Integer sst, String sd) {
    }

    public record QosFlow(Integer qfi, @JsonProperty("5qi") Integer fiveQi) {
    }
}
