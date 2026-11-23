package gr.pnet.em.nef.pcf;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import gr.pnet.em.config.Open5gsProperties;
import gr.pnet.em.generated.pcfclient.ApplicationSessionsCollectionApi;
import gr.pnet.em.generated.pcfclient.IndividualApplicationSessionContextDocumentApi;
import gr.pnet.em.generated.pcfclient.invoker.ApiClient;
import gr.pnet.em.support.OutboundCalls;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;

@Configuration
public class PcfClientConfiguration {

    @Bean
    public ApiClient pcfApiClient(Open5gsProperties properties) {
        // Open5GS SBI speaks cleartext HTTP/2 without an upgrade (prior knowledge), which only OkHttp offers here
        OkHttpClient httpClient = new OkHttpClient.Builder()
                .protocols(List.of(Protocol.H2_PRIOR_KNOWLEDGE))
                .addInterceptor(OutboundCalls.okHttp("PCF", false))
                .build();
        ApiClient apiClient = new ApiClient(httpClient);
        apiClient.setBasePath(properties.pcf().baseUrl().toString());
        return apiClient;
    }

    @Bean
    public ApplicationSessionsCollectionApi pcfAppSessionsApi(ApiClient pcfApiClient) {
        return new ApplicationSessionsCollectionApi(pcfApiClient);
    }

    @Bean
    public IndividualApplicationSessionContextDocumentApi pcfAppSessionApi(ApiClient pcfApiClient) {
        return new IndividualApplicationSessionContextDocumentApi(pcfApiClient);
    }
}
