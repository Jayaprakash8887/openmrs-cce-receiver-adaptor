package org.openphc.cce.receiver.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Beans of the Beckn endpoints. Loaded only when {@code receiver.beckn.enabled=true}: with it off,
 * nothing Beckn exists and {@code /beckn/**} answers 404.
 */
@Configuration
@ConditionalOnProperty(prefix = "receiver.beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BecknProperties.class)
public class BecknConfig {

    public static final String ONIX_CLIENT = "becknOnixRestClient";
    public static final String ON_UPDATE_EXECUTOR = "becknOnUpdateExecutor";

    private static final Duration ONIX_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration ONIX_READ_TIMEOUT = Duration.ofSeconds(15);

    public BecknConfig(BecknProperties props) {
        // Fail at start, not at the first referral.
        props.validate();
    }

    /**
     * The JDK HTTP client, not {@code HttpURLConnection}: it never repeats a POST by itself, so
     * {@code on_update} is sent at most once.
     */
    @Bean(ONIX_CLIENT)
    public RestClient becknOnixRestClient(BecknProperties props) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(ONIX_CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(ONIX_READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(withoutTrailingSlash(props.onix().bppCallerUrl()))
                .requestFactory(factory)
                .build();
    }

    /** Sends {@code on_update} after the Ack. A full queue drops it: Spice already has its Ack. */
    @Bean(ON_UPDATE_EXECUTOR)
    public Executor becknOnUpdateExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("beckn-on-update-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        return ex;
    }

    static String withoutTrailingSlash(String url) {
        String u = url == null ? "" : url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
