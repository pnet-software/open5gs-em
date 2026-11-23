package gr.pnet.em.tep.status;

import java.util.Set;

import gr.pnet.em.generated.tep.model.TepSubscriberStatus;
import gr.pnet.em.generated.tep.model.TepSubscriberStatus.ConnectionStateEnum;
import gr.pnet.em.generated.tep.model.TepSubscriberStatusPduSession;
import gr.pnet.em.generated.tep.model.TepSubscriberStatusQosFlow;
import gr.pnet.em.support.SliceDifferentiators;

/** Combines a UE's AMF context (registration, location) and SMF sessions into a TEP subscriber status. */
final class SubscriberStatusMapper {

    private SubscriberStatusMapper() {
    }

    /**
     * Either may be null: a UE can be registered without PDU sessions. Without an AMF context it's deregistered. The
     * UE is roaming when its tracking area's PLMN isn't one of the home PLMNs; without either, that can't be told.
     */
    static TepSubscriberStatus toTep(UeSessions.UeSession session, UeContexts.UeContext context,
            Set<String> homePlmns) {
        TepSubscriberStatus status = new TepSubscriberStatus(connectionState(context));
        UeContexts.NrTai tai = context == null || context.location() == null ? null : context.location().nrTai();
        if (tai != null) {
            status.setMcc(mcc(tai.plmn()));
            status.setMnc(mnc(tai.plmn()));
            if (status.getMcc() != null && status.getMnc() != null && !homePlmns.isEmpty()) {
                status.setRoaming(!homePlmns.contains(tai.plmn()));
            }
        }
        if (session != null && session.pdu() != null) {
            status.setPduSessions(session.pdu().stream().map(SubscriberStatusMapper::toTep).toList());
        }
        return status;
    }

    // Registered unless the AMF has no context for the UE or says otherwise; then its CM state
    private static ConnectionStateEnum connectionState(UeContexts.UeContext context) {
        if (context == null || (context.mmState() != null && !"registered".equalsIgnoreCase(context.mmState()))) {
            return ConnectionStateEnum.DEREGISTERED;
        }
        return "connected".equalsIgnoreCase(context.cmState()) ? ConnectionStateEnum.CONNECTED
                : ConnectionStateEnum.IDLE;
    }

    private static TepSubscriberStatusPduSession toTep(UeSessions.Pdu pdu) {
        UeSessions.Snssai snssai = pdu.snssai();
        return new TepSubscriberStatusPduSession()
                .pduSessionId(pdu.psi() == null ? null : String.valueOf(pdu.psi()))
                .dnn(pdu.dnn())
                .sst(snssai == null || snssai.sst() == null ? null : String.valueOf(snssai.sst()))
                // The core reports a slice without an SD with sd ffffff
                .sd(snssai == null ? null : SliceDifferentiators.present(snssai.sd()))
                .pduAddress(pdu.ipv4())
                .qosFlows(pdu.qosFlows() == null ? null : pdu.qosFlows().stream()
                        .map(flow -> new TepSubscriberStatusQosFlow(flow.qfi(), flow.fiveQi()))
                        .toList());
    }

    // The PLMN is the 3-digit MCC followed by a 2- or 3-digit MNC
    private static String mcc(String plmn) {
        return plmn != null && plmn.length() >= 3 ? plmn.substring(0, 3) : null;
    }

    private static String mnc(String plmn) {
        return plmn != null && plmn.length() > 3 ? plmn.substring(3) : null;
    }
}
