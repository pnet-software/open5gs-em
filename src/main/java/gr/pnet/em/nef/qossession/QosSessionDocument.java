package gr.pnet.em.nef.qossession;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;

/**
 * An AS session with QoS and the PCF app session realizing it.
 *
 * @param id                      the session id in the subscription's URI; unique across all SCS/ASs
 * @param appSessionId            the PCF's app session id (last segment of appSessionUri)
 * @param appSessionUri           the app session's URI at the PCF
 * @param fiveQi                  the 5QI of the profile the app session was set up with; null for a session stored
 *                                before it was recorded
 * @param notificationDestination where the SCS/AS takes notifications
 * @param subscription            the subscription as the SCS/AS sees it
 */
@Document("qos_sessions")
record QosSessionDocument(@Id String id, String scsAsId, String appSessionId, String appSessionUri, Integer fiveQi,
        String notificationDestination, AsSessionWithQoSSubscription subscription) {

    /** The session with this subscription, on the same app session. */
    QosSessionDocument withSubscription(AsSessionWithQoSSubscription subscription) {
        return new QosSessionDocument(id, scsAsId, appSessionId, appSessionUri, fiveQi,
                subscription.getNotificationDestination(), subscription);
    }

    /** The session with this subscription, on a new app session set up with this 5QI. */
    QosSessionDocument withAppSession(String appSessionId, String appSessionUri, Integer fiveQi,
            AsSessionWithQoSSubscription subscription) {
        return new QosSessionDocument(id, scsAsId, appSessionId, appSessionUri, fiveQi,
                subscription.getNotificationDestination(), subscription);
    }
}
