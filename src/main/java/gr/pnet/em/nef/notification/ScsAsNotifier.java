package gr.pnet.em.nef.notification;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import gr.pnet.em.generated.scsasclient.AsSessionWithQoSApiSubscriptionEventsNotificationCallbackApi;
import gr.pnet.em.generated.scsasclient.invoker.ApiClient;
import gr.pnet.em.generated.scsasclient.invoker.ApiException;
import gr.pnet.em.generated.scsasclient.model.UserPlaneEvent;
import gr.pnet.em.generated.scsasclient.model.UserPlaneEventReport;
import gr.pnet.em.generated.scsasclient.model.UserPlaneNotificationData;
import gr.pnet.em.support.OutboundCalls;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;

/**
 * Sends session events to the SCS/AS. The generated callback client posts to
 * {@code {notificationDestination}/notifications}, as the 3GPP spec's /notifications path describes.
 */
@Component
public class ScsAsNotifier {

    private static final Logger log = LoggerFactory.getLogger(ScsAsNotifier.class);

    // One connection pool for every SCS/AS; each notification only gets its own base path
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .addInterceptor(OutboundCalls.okHttp("SCS/AS", true))
            .build();

    /** Tells the SCS/AS the network ended its session. Best effort: a failure is logged, not raised. */
    public void notifyTermination(String notificationDestination, String sessionId) {
        UserPlaneNotificationData notification = new UserPlaneNotificationData()
                .transaction(sessionId)
                .eventReports(List.of(new UserPlaneEventReport().event(UserPlaneEvent.SESSION_TERMINATION)));
        ApiClient apiClient = new ApiClient(httpClient).setBasePath(notificationDestination);
        try {
            new AsSessionWithQoSApiSubscriptionEventsNotificationCallbackApi(apiClient).notificationsPost(notification);
            log.info("Notified {} that QoS session {} was terminated", host(notificationDestination), sessionId);
        } catch (ApiException e) {
            // Never the response body or the whole URL, which the SCS/AS chose: only its host
            log.warn("Couldn't notify {} that QoS session {} was terminated (HTTP {}): {}",
                    host(notificationDestination), sessionId, e.getCode(),
                    e.getCause() == null ? "rejected" : e.getCause().getClass().getSimpleName());
        }
    }

    private static String host(String url) {
        HttpUrl parsed = HttpUrl.parse(url);
        return parsed == null ? "an invalid URL" : parsed.host();
    }
}
