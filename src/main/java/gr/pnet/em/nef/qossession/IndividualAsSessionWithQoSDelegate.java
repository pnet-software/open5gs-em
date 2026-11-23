package gr.pnet.em.nef.qossession;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import gr.pnet.em.error.NotImplementedException;
import gr.pnet.em.generated.tep.IndividualAsSessionWithRequiredQoSSubscriptionApiDelegate;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscriptionPatch;
import gr.pnet.em.generated.tep.model.UserPlaneNotificationData;

/** /tep-api/v1/3gpp-as-session-with-qos/v1/{scsAsId}/subscriptions/{subscriptionId} */
@Service
public class IndividualAsSessionWithQoSDelegate implements IndividualAsSessionWithRequiredQoSSubscriptionApiDelegate {

    private final QosSessionService sessions;

    public IndividualAsSessionWithQoSDelegate(QosSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public ResponseEntity<AsSessionWithQoSSubscription> fetchIndASSessionWithQoSSubscription(String scsAsId,
            String subscriptionId) {
        return ResponseEntity.ok(sessions.get(scsAsId, subscriptionId));
    }

    @Override
    public ResponseEntity<UserPlaneNotificationData> deleteIndASSessionWithQoSSubscription(String scsAsId,
            String subscriptionId) {
        return ResponseEntity.ok(sessions.delete(scsAsId, subscriptionId));
    }

    @Override
    public ResponseEntity<AsSessionWithQoSSubscription> updateIndASSessionWithQoSSubscription(String scsAsId,
            String subscriptionId, AsSessionWithQoSSubscription subscription) {
        throw new NotImplementedException("Replacing a QoS session isn't supported: the Open5GS PCF can't replace "
                + "an app session. Delete the session and create a new one");
    }

    @Override
    public ResponseEntity<AsSessionWithQoSSubscription> modifyIndASSessionWithQoSSubscription(String scsAsId,
            String subscriptionId, AsSessionWithQoSSubscriptionPatch patch) {
        return ResponseEntity.ok(sessions.update(scsAsId, subscriptionId, patch));
    }
}
