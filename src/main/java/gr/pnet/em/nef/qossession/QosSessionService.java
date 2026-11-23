package gr.pnet.em.nef.qossession;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gr.pnet.em.error.InvalidRequestException;
import gr.pnet.em.error.ResourceNotFoundException;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscriptionPatch;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.generated.tep.model.UserPlaneEvent;
import gr.pnet.em.generated.tep.model.UserPlaneEventReport;
import gr.pnet.em.generated.tep.model.UserPlaneNotificationData;
import gr.pnet.em.nef.notification.ScsAsNotifier;
import gr.pnet.em.nef.pcf.PcfPolicyAuthorizationClient;
import gr.pnet.em.tep.qualityprofile.QualityProfileService;
import gr.pnet.em.tep.subscriber.PccRuleService;

/** AS sessions with QoS (TS 29.122): each one is an app session on the PCF, tracked in qos_sessions. */
@Service
public class QosSessionService {

    private static final Logger log = LoggerFactory.getLogger(QosSessionService.class);
    private static final String QOS_REFERENCE = "/qosReference";

    private final QosSessionRepository repository;
    private final QualityProfileService profiles;
    private final PccRuleService pccRules;
    private final PcfPolicyAuthorizationClient pcf;
    private final ScsAsNotifier notifier;

    public QosSessionService(QosSessionRepository repository, QualityProfileService profiles,
            PccRuleService pccRules, PcfPolicyAuthorizationClient pcf, ScsAsNotifier notifier) {
        this.repository = repository;
        this.profiles = profiles;
        this.pccRules = pccRules;
        this.pcf = pcf;
        this.notifier = notifier;
    }

    public List<AsSessionWithQoSSubscription> list(String scsAsId) {
        return repository.findAllByScsAsId(scsAsId).stream().map(QosSessionDocument::subscription).toList();
    }

    public AsSessionWithQoSSubscription get(String scsAsId, String id) {
        return find(scsAsId, id).subscription();
    }

    /**
     * Sets up the session with the quality profile its qosReference names. The UE's PDU session first gets a PCC
     * rule for the profile's 5QI, if it has none: the PCF only authorizes the session with one.
     *
     * @param selfLink the subscription's URI for a session id
     * @return the subscription, with its self link
     */
    public AsSessionWithQoSSubscription create(String scsAsId, AsSessionWithQoSSubscription subscription,
            Function<String, URI> selfLink) {
        TepQualityProfile profile = profileFor(subscription.getQosReference());
        String ueIpv4Addr = ueIpv4Addr(subscription);
        pccRules.ensurePccRule(ueIpv4Addr, profile.getFiveQi(), fiveQisInUse(ueIpv4Addr), true);
        String id = UUID.randomUUID().toString();
        String appSessionUri = pcf.createAppSession(scsAsId, id, subscription, profile);
        String appSessionId = appSessionId(appSessionUri);
        subscription.setSelf(selfLink.apply(id).toString());
        storeOrDelete(appSessionId, () -> repository.insert(new QosSessionDocument(id, scsAsId, appSessionId,
                appSessionUri, profile.getFiveQi(), subscription.getNotificationDestination(), subscription)));
        return subscription;
    }

    /**
     * Changes the session as the SCS/AS's merge patch says: its quality profile, flows or notification destination.
     * A profile with the 5QI the app session has changes the app session in place. One with another 5QI replaces it:
     * for a new 5QI the Open5GS PCF adds a PCC rule to the app session and keeps the old one, so a new app session is
     * created, and only then the old one deleted.
     */
    public AsSessionWithQoSSubscription update(String scsAsId, String id, AsSessionWithQoSSubscriptionPatch patch) {
        rejectWhatCanNotChange(patch);
        QosSessionDocument session = find(scsAsId, id);
        AsSessionWithQoSSubscription subscription = session.subscription();
        if (patch.getQosReference() == null && patch.getFlowInfo() == null
                && patch.getNotificationDestination() == null) {
            return subscription;
        }
        TepQualityProfile profile = patch.getQosReference() == null
                ? configuredProfile(subscription.getQosReference())
                : profileFor(patch.getQosReference());
        if (patch.getQosReference() != null) {
            subscription.setQosReference(patch.getQosReference());
        }
        if (patch.getFlowInfo() != null) {
            subscription.setFlowInfo(patch.getFlowInfo());
        }
        if (patch.getNotificationDestination() != null) {
            subscription.setNotificationDestination(patch.getNotificationDestination());
        }

        // Another 5QI takes a new app session, and so a QoS flow more until the old one goes
        boolean newAppSession = !profile.getFiveQi().equals(session.fiveQi());
        String ueIpv4Addr = ueIpv4Addr(subscription);
        pccRules.ensurePccRule(ueIpv4Addr, profile.getFiveQi(), fiveQisInUse(ueIpv4Addr), newAppSession);
        if (!newAppSession) {
            pcf.updateAppSession(session.appSessionId(), subscription, profile);
            repository.save(session.withSubscription(subscription));
            return subscription;
        }
        String appSessionUri = pcf.createAppSession(scsAsId, id, subscription, profile);
        String appSessionId = appSessionId(appSessionUri);
        storeOrDelete(appSessionId, () -> repository.save(
                session.withAppSession(appSessionId, appSessionUri, profile.getFiveQi(), subscription)));
        log.info("QoS session {} of SCS/AS {} moved to PCF app session {} for 5QI {}", id, scsAsId, appSessionId,
                profile.getFiveQi());
        try {
            pcf.deleteAppSession(session.appSessionId());
        } catch (RuntimeException e) {
            // The session runs on the new app session either way; the old one only keeps its PCC rule until it goes
            log.error("QoS session {} of SCS/AS {} left its old PCF app session {} behind: {}", id, scsAsId,
                    session.appSessionId(), e.getMessage());
        }
        return subscription;
    }

    /** Ends the session at the SCS/AS's request; answers with the termination report. */
    public UserPlaneNotificationData delete(String scsAsId, String id) {
        QosSessionDocument session = find(scsAsId, id);
        pcf.deleteAppSession(session.appSessionId());
        repository.delete(session);
        return new UserPlaneNotificationData()
                .transaction(id)
                .eventReports(List.of(new UserPlaneEventReport().event(UserPlaneEvent.SESSION_TERMINATION)));
    }

    /**
     * The PCF ended the session: tell the SCS/AS and forget the session (the app session is already gone). The end of
     * an app session the session has since moved off ({@code appSessionUri}, when the PCF gives it) is ignored.
     */
    public void terminatedByNetwork(String scsAsId, String id, String appSessionUri) {
        QosSessionDocument session = find(scsAsId, id);
        if (appSessionUri != null && !session.appSessionId().equals(appSessionId(appSessionUri))) {
            log.info("PCF ended app session {}, which QoS session {} of SCS/AS {} no longer runs on", appSessionUri,
                    id, scsAsId);
            return;
        }
        log.info("PCF terminated QoS session {} of SCS/AS {}", id, scsAsId);
        notifier.notifyTermination(session.notificationDestination(), id);
        repository.delete(session);
    }

    private QosSessionDocument find(String scsAsId, String id) {
        return repository.findByScsAsIdAndId(scsAsId, id).orElseThrow(() -> new ResourceNotFoundException(
                "QoS session " + id + " of SCS/AS " + scsAsId + " not found"));
    }

    // The 5QIs of the sessions on this UE address: their PCC rules must stay. A session stored before its 5QI was
    // recorded has its profile's, if that is still configured.
    private Set<Integer> fiveQisInUse(String ueIpv4Addr) {
        Set<Integer> inUse = new HashSet<>();
        for (QosSessionDocument session : repository.findAllBySubscription_UeIpv4Addr(ueIpv4Addr)) {
            Integer fiveQi = session.fiveQi() != null ? session.fiveQi()
                    : configuredFiveQi(session.subscription().getQosReference());
            if (fiveQi != null) {
                inUse.add(fiveQi);
            }
        }
        return inUse;
    }

    private Integer configuredFiveQi(String qosReference) {
        if (qosReference == null) {
            return null;
        }
        try {
            return profiles.get(qosReference).getFiveQi();
        } catch (ResourceNotFoundException e) {
            return null;
        }
    }

    private static String appSessionId(String appSessionUri) {
        return appSessionUri.substring(appSessionUri.lastIndexOf('/') + 1);
    }

    // Don't leave an app session on the PCF that nothing tracks
    private void storeOrDelete(String appSessionId, Runnable store) {
        try {
            store.run();
        } catch (RuntimeException e) {
            log.error("Couldn't store a QoS session; deleting its PCF app session {}", appSessionId);
            pcf.deleteAppSession(appSessionId);
            throw e;
        }
    }

    // TEP applies a new profile, flows and notification destination. Nothing else of a session can change.
    private static void rejectWhatCanNotChange(AsSessionWithQoSSubscriptionPatch patch) {
        Map<String, Object> fixed = new LinkedHashMap<>();
        fixed.put("exterAppId", patch.getExterAppId());
        fixed.put("ethFlowInfo", patch.getEthFlowInfo());
        fixed.put("enEthFlowInfo", patch.getEnEthFlowInfo());
        fixed.put("altQoSReferences", patch.getAltQoSReferences());
        fixed.put("altQosReqs", patch.getAltQosReqs());
        fixed.put("disUeNotif", patch.getDisUeNotif());
        fixed.put("usageThreshold", patch.getUsageThreshold());
        fixed.put("qosMonInfo", patch.getQosMonInfo());
        fixed.put("directNotifInd", patch.getDirectNotifInd());
        fixed.put("tscQosReq", patch.getTscQosReq());
        fixed.put("events", patch.getEvents());
        fixed.forEach((field, value) -> {
            if (value != null) {
                throw new InvalidRequestException("/" + field, field
                        + " can't be changed: only qosReference, flowInfo and notificationDestination can");
            }
        });
    }

    // TEP finds the UE's PDU session, and the PCF its policy session, by the UE's IPv4 address
    private static String ueIpv4Addr(AsSessionWithQoSSubscription subscription) {
        if (subscription.getUeIpv4Addr() == null) {
            throw new InvalidRequestException("/ueIpv4Addr", "ueIpv4Addr is required: the UE is found by its IPv4 address");
        }
        return subscription.getUeIpv4Addr();
    }

    // The profile must exist and be active. Whether the PCF has a QoS for it, only the PCF can say.
    private TepQualityProfile profileFor(String qosReference) {
        if (qosReference == null) {
            throw new InvalidRequestException(QOS_REFERENCE, "qosReference is required: the name of a quality profile");
        }
        TepQualityProfile profile;
        try {
            profile = profiles.get(qosReference);
        } catch (ResourceNotFoundException e) {
            throw new InvalidRequestException(QOS_REFERENCE, "No quality profile is named " + qosReference);
        }
        if (profile.getStatus() != TepQualityProfile.StatusEnum.ACTIVE) {
            throw new InvalidRequestException(QOS_REFERENCE,
                    "Quality profile " + qosReference + " is " + profile.getStatus().getValue() + ", not ACTIVE");
        }
        return profile;
    }

    // The profile a session already has. It may have been deprecated since, but it must still be configured.
    private TepQualityProfile configuredProfile(String qosReference) {
        try {
            return profiles.get(qosReference);
        } catch (ResourceNotFoundException e) {
            throw new InvalidRequestException(QOS_REFERENCE, "The session's quality profile " + qosReference
                    + " is no longer configured: name another in qosReference");
        }
    }
}
