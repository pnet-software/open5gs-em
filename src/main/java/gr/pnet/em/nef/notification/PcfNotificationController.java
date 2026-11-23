package gr.pnet.em.nef.notification;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import gr.pnet.em.nef.qossession.QosSessionService;

/**
 * Where the PCF reports that an app session ended (Npcf_PolicyAuthorization_Notify, termination). Southbound, so
 * outside /tep-api: only the PCF calls it, and the ingress doesn't expose it.
 */
@RestController
public class PcfNotificationController {

    private final QosSessionService sessions;

    public PcfNotificationController(QosSessionService sessions) {
        this.sessions = sessions;
    }

    /** The body is the PCF's TerminationInfo, whose resUri names the app session that ended. */
    @PostMapping("/notifications/{scsAsId}/{sessionId}/terminate")
    public ResponseEntity<Void> terminate(@PathVariable String scsAsId, @PathVariable String sessionId,
            @RequestBody(required = false) Map<String, Object> terminationInfo) {
        Object resUri = terminationInfo == null ? null : terminationInfo.get("resUri");
        sessions.terminatedByNetwork(scsAsId, sessionId, resUri instanceof String uri ? uri : null);
        return ResponseEntity.noContent().build();
    }
}
