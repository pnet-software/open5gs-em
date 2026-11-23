package gr.pnet.em.nef.qossession;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import gr.pnet.em.generated.tep.AsSessionWithRequiredQoSSubscriptionsApiDelegate;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;
import gr.pnet.em.generated.tep.model.IpAddr;
import gr.pnet.em.support.Locations;

/** /tep-api/v1/3gpp-as-session-with-qos/v1/{scsAsId}/subscriptions */
@Service
public class AsSessionWithQoSDelegate implements AsSessionWithRequiredQoSSubscriptionsApiDelegate {

    private final QosSessionService sessions;

    public AsSessionWithQoSDelegate(QosSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public ResponseEntity<AsSessionWithQoSSubscription> createASSessionWithQoSSubscription(String scsAsId,
            AsSessionWithQoSSubscription subscription) {
        AsSessionWithQoSSubscription created = sessions.create(scsAsId, subscription, Locations::childOfCurrentRequest);
        return ResponseEntity.created(URI.create(created.getSelf())).body(created);
    }

    // The UE filters (ip-addrs, ip-domain, mac-addrs) aren't supported: every session of the SCS/AS is returned
    @Override
    public ResponseEntity<List<AsSessionWithQoSSubscription>> fetchAllASSessionWithQoSSubscriptions(String scsAsId,
            List<IpAddr> ipAddrs, String ipDomain, List<String> macAddrs) {
        return ResponseEntity.ok(sessions.list(scsAsId));
    }
}
