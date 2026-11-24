package gr.pnet.em.nef.pcf;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gr.pnet.em.config.Open5gsProperties;
import gr.pnet.em.error.UpstreamException;
import gr.pnet.em.generated.pcfclient.ApplicationSessionsCollectionApi;
import gr.pnet.em.generated.pcfclient.IndividualApplicationSessionContextDocumentApi;
import gr.pnet.em.generated.pcfclient.invoker.ApiException;
import gr.pnet.em.generated.pcfclient.invoker.ApiResponse;
import gr.pnet.em.generated.pcfclient.model.AppSessionContext;
import gr.pnet.em.generated.pcfclient.model.AppSessionContextReqData;
import gr.pnet.em.generated.pcfclient.model.AppSessionContextUpdateData;
import gr.pnet.em.generated.pcfclient.model.AppSessionContextUpdateDataPatch;
import gr.pnet.em.generated.pcfclient.model.FlowStatus;
import gr.pnet.em.generated.pcfclient.model.FlowUsage;
import gr.pnet.em.generated.pcfclient.model.MediaComponent;
import gr.pnet.em.generated.pcfclient.model.MediaComponentRm;
import gr.pnet.em.generated.pcfclient.model.MediaSubComponent;
import gr.pnet.em.generated.pcfclient.model.MediaSubComponentRm;
import gr.pnet.em.generated.pcfclient.model.PreemptionCapability;
import gr.pnet.em.generated.pcfclient.model.PreemptionCapabilityRm;
import gr.pnet.em.generated.pcfclient.model.PreemptionVulnerability;
import gr.pnet.em.generated.pcfclient.model.PreemptionVulnerabilityRm;
import gr.pnet.em.generated.tep.model.AsSessionWithQoSSubscription;
import gr.pnet.em.generated.tep.model.FlowInfo;
import gr.pnet.em.generated.tep.model.TepQualityProfile;
import gr.pnet.em.tep.qualityprofile.GbrFiveQis;

/** AS sessions with QoS as Npcf_PolicyAuthorization app sessions on the Open5GS PCF. */
@Service
public class PcfPolicyAuthorizationClient {

    private static final Logger log = LoggerFactory.getLogger(PcfPolicyAuthorizationClient.class);
    private static final int NOT_FOUND = 404;

    private final ApplicationSessionsCollectionApi appSessions;
    private final IndividualApplicationSessionContextDocumentApi appSession;
    private final URI notificationUrl;
    private final GbrFiveQis gbrFiveQis;

    public PcfPolicyAuthorizationClient(ApplicationSessionsCollectionApi appSessions,
            IndividualApplicationSessionContextDocumentApi appSession, Open5gsProperties properties,
            GbrFiveQis gbrFiveQis) {
        this.appSessions = appSessions;
        this.appSession = appSession;
        this.notificationUrl = properties.pcf().notificationUrl();
        this.gbrFiveQis = gbrFiveQis;
    }

    /**
     * Creates the app session that realizes the subscription with the profile, and returns its URI (the PCF's
     * Location). The PCF reports the session's end to {@code {notificationUrl}/{scsAsId}/{sessionId}/terminate}.
     */
    public String createAppSession(String scsAsId, String sessionId, AsSessionWithQoSSubscription subscription,
            TepQualityProfile profile) {
        try {
            ApiResponse<AppSessionContext> response = appSessions.postAppSessionsWithHttpInfo(
                    appSessionContext(scsAsId, sessionId, subscription, profile));
            String location = header(response.getHeaders(), "location");
            if (location == null) {
                throw new UpstreamException("The PCF created the app session but returned no Location");
            }
            log.info("PCF app session {} created for QoS session {} of SCS/AS {}", location, sessionId, scsAsId);
            return location;
        } catch (ApiException e) {
            throw upstream("create the app session", e);
        }
    }

    /**
     * Changes the app session to the subscription's flows and the profile's QoS. The profile must have the 5QI the
     * app session was created with: for another 5QI the Open5GS PCF adds a PCC rule and keeps the old one. The whole
     * media component is sent, flows included, as the PCF takes a component without flows as one whose flows are
     * gone.
     */
    public void updateAppSession(String appSessionId, AsSessionWithQoSSubscription subscription,
            TepQualityProfile profile) {
        AppSessionContextUpdateData update = new AppSessionContextUpdateData();
        update.setMedComponents(Map.of("1", mediaComponentRm(subscription, profile)));
        update.setTsnPortManContNwtts(null);
        AppSessionContextUpdateDataPatch patch = new AppSessionContextUpdateDataPatch();
        patch.setAscReqData(update);
        try {
            // The PCF answers with the patch it applied, not an AppSessionContext, so its answer isn't read
            appSession.getApiClient().execute(appSession.modAppSessionCall(appSessionId, patch, null));
            log.info("PCF app session {} updated to {}", appSessionId, profile.getName());
        } catch (ApiException e) {
            throw upstream("update app session " + appSessionId, e);
        }
    }

    /** Deletes an app session; one the PCF no longer knows counts as deleted. */
    public void deleteAppSession(String appSessionId) {
        try {
            appSession.deleteAppSessionWithHttpInfo(appSessionId, null);
            log.info("PCF app session {} deleted", appSessionId);
        } catch (ApiException e) {
            if (e.getCode() != NOT_FOUND) {
                throw upstream("delete app session " + appSessionId, e);
            }
            log.warn("PCF app session {} was already gone", appSessionId);
        }
    }

    private AppSessionContext appSessionContext(String scsAsId, String sessionId,
            AsSessionWithQoSSubscription subscription, TepQualityProfile profile) {
        // One media sub-component per flow of the subscription
        Map<String, MediaSubComponent> subComponents = new LinkedHashMap<>();
        for (FlowInfo flow : flows(subscription)) {
            MediaSubComponent subComponent = new MediaSubComponent();
            subComponent.setfNum(flow.getFlowId());
            if (flow.getFlowDescriptions() != null) {
                flow.getFlowDescriptions().forEach(subComponent::addFDescsItem);
            }
            subComponent.setFlowUsage(FlowUsage.NO_INFO);
            subComponents.put(String.valueOf(flow.getFlowId()), subComponent);
        }

        MediaComponent component = new MediaComponent();
        component.setMedCompN(1);
        component.setMedSubComps(subComponents);
        component.setfStatus(FlowStatus.ENABLED);
        // The PCF picks the 5QI by the reference, from its qos_profiles. With a reference it ignores the media
        // type, so none is sent.
        component.setQosReference(profile.getName());
        // MBR and GBR, for a GBR 5QI only (see flowBitRates); without a minimum, the PCF sets the GBR to the MBR
        if (flowBitRates(profile)) {
            component.setMarBwDl(BitRates.of(profile.getMaxDownstreamRate()));
            component.setMarBwUl(BitRates.of(profile.getMaxUpstreamRate()));
            component.setMirBwDl(BitRates.of(profile.getTargetMinDownstreamRate()));
            component.setMirBwUl(BitRates.of(profile.getTargetMinUpstreamRate()));
        }
        // ARP; the PCF maps the reservation priority to a priority level with its reservation_priorities
        component.setResPrio(ReservationPriorities.of(profile.getPriority()));
        // TEP's pre-emption values are the 3GPP ones
        if (profile.getPreemptionCapability() != null) {
            component.setPreemptCap(PreemptionCapability.fromValue(profile.getPreemptionCapability().getValue()));
        }
        if (profile.getPreemptionVulnerability() != null) {
            component.setPreemptVuln(PreemptionVulnerability.fromValue(profile.getPreemptionVulnerability().getValue()));
        }

        AppSessionContextReqData request = new AppSessionContextReqData();
        request.setNotifUri(notificationUrl + "/" + scsAsId + "/" + sessionId);
        request.setSuppFeat(subscription.getSupportedFeatures());
        request.setUeIpv4(subscription.getUeIpv4Addr());
        request.setMedComponents(Map.of("1", component));

        AppSessionContext context = new AppSessionContext();
        context.setAscReqData(request);
        return context;
    }

    // The media component of an update: the same as on create, in the update's types
    private MediaComponentRm mediaComponentRm(AsSessionWithQoSSubscription subscription,
            TepQualityProfile profile) {
        MediaComponentRm component = new MediaComponentRm();
        component.setMedCompN(1);
        component.setCodecs(null);
        for (FlowInfo flow : flows(subscription)) {
            MediaSubComponentRm subComponent = new MediaSubComponentRm();
            subComponent.setfNum(flow.getFlowId());
            if (flow.getFlowDescriptions() != null) {
                flow.getFlowDescriptions().forEach(subComponent::addFDescsItem);
            }
            subComponent.setFlowUsage(FlowUsage.NO_INFO);
            component.putMedSubCompsItem(String.valueOf(flow.getFlowId()), subComponent);
        }
        component.setfStatus(FlowStatus.ENABLED);
        component.setQosReference(profile.getName());
        if (flowBitRates(profile)) {
            component.setMarBwDl(BitRates.of(profile.getMaxDownstreamRate()));
            component.setMarBwUl(BitRates.of(profile.getMaxUpstreamRate()));
            component.setMirBwDl(BitRates.of(profile.getTargetMinDownstreamRate()));
            component.setMirBwUl(BitRates.of(profile.getTargetMinUpstreamRate()));
        }
        component.setResPrio(ReservationPriorities.of(profile.getPriority()));
        if (profile.getPreemptionCapability() != null) {
            component.setPreemptCap(PreemptionCapabilityRm.fromValue(profile.getPreemptionCapability().getValue()));
        }
        if (profile.getPreemptionVulnerability() != null) {
            component.setPreemptVuln(
                    PreemptionVulnerabilityRm.fromValue(profile.getPreemptionVulnerability().getValue()));
        }
        return component;
    }

    /**
     * Whether the media component carries the profile's bit rates: only for a GBR 5QI. A non-GBR QoS flow has no flow
     * bit rates (TS 23.501 clause 5.7.2.5), and the SMF signals GBR QoS flow information for any flow that has them,
     * so a non-GBR profile's rates aren't sent. The PCC rule TEP gives a non-GBR 5QI has none either, so the PCF has
     * none to fall back to. Such a flow is limited by the Session-AMBR and UE-AMBR.
     */
    private boolean flowBitRates(TepQualityProfile profile) {
        return gbrFiveQis.isGbr(profile.getFiveQi());
    }

    private static List<FlowInfo> flows(AsSessionWithQoSSubscription subscription) {
        return subscription.getFlowInfo() == null ? List.of() : subscription.getFlowInfo();
    }

    private static String header(Map<String, List<String>> headers, String name) {
        return headers.entrySet().stream()
                .filter(header -> header.getKey().equalsIgnoreCase(name) && !header.getValue().isEmpty())
                .map(header -> header.getValue().get(0))
                .findFirst().orElse(null);
    }

    private static UpstreamException upstream(String action, ApiException e) {
        // Code 0: no HTTP answer at all (connection refused, timeout); the cause says which
        String reason = e.getCode() == 0
                ? "PCF unreachable: " + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage())
                : "PCF answered " + e.getCode() + ": " + e.getResponseBody();
        return new UpstreamException("Couldn't " + action + ". " + reason, e);
    }
}
