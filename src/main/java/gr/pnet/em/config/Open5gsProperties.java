package gr.pnet.em.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Where the Open5GS network functions this app talks to are. */
@Validated
@ConfigurationProperties("open5gs")
public record Open5gsProperties(@Valid @NotNull Pcf pcf, @Valid @NotNull Metrics metrics) {

    /**
     * @param baseUrl         Npcf_PolicyAuthorization API root, e.g. http://127.0.0.13:7777/npcf-policyauthorization/v1
     * @param notificationUrl this app's /notifications as the PCF can reach it; the PCF posts session events there
     */
    public record Pcf(@NotNull URI baseUrl, @NotNull URI notificationUrl) {
    }

    /**
     * @param amfUrl the AMF's ue-info endpoint (registered UEs: GUTI, location)
     * @param smfUrl the SMF's pdu-info endpoint (PDU sessions)
     */
    public record Metrics(@NotNull URI amfUrl, @NotNull URI smfUrl, @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout) {
    }
}
