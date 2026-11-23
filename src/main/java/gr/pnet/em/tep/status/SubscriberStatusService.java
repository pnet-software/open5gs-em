package gr.pnet.em.tep.status;

import java.util.Set;

import org.bson.Document;
import org.springframework.stereotype.Service;

import gr.pnet.em.generated.tep.model.TepSubscriberStatus;
import gr.pnet.em.tep.subscriber.SubscriberService;

/** Real-time status: the subscriber's UE as the AMF and SMF currently see it, matched by SUPI. */
@Service
public class SubscriberStatusService {

    private final SubscriberService subscribers;
    private final Open5gsMetricsClient metrics;
    private final Set<String> homePlmns;

    public SubscriberStatusService(SubscriberService subscribers, Open5gsMetricsClient metrics,
            HomePlmnProperties homePlmns) {
        this.subscribers = subscribers;
        this.metrics = metrics;
        this.homePlmns = homePlmns.plmns();
    }

    /** The status of the subscriber with this phone number. */
    public TepSubscriberStatus status(String phoneNumber) {
        String supi = supi(phoneNumber);
        UeSessions.UeSession session = metrics.ueSessions().stream()
                .filter(s -> supi.equals(s.supi()))
                .findFirst().orElse(null);
        return SubscriberStatusMapper.toTep(session, ueContextOf(supi), homePlmns);
    }

    /** The UE of the subscriber with this phone number as the AMF sees it (registration, location); null if it isn't registered. */
    public UeContexts.UeContext ueContext(String phoneNumber) {
        return ueContextOf(supi(phoneNumber));
    }

    private String supi(String phoneNumber) {
        Document subscriber = subscribers.findByPhoneNumber(phoneNumber);
        return "imsi-" + subscriber.getString("imsi");
    }

    private UeContexts.UeContext ueContextOf(String supi) {
        return metrics.ueContexts().stream()
                .filter(c -> supi.equals(c.supi()))
                .findFirst().orElse(null);
    }
}
