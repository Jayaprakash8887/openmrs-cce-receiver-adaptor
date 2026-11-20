package org.openphc.cce.receiver.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Beans of the Sygnal endpoint. Loaded only when {@code receiver.beckn.enabled=true}: with it off,
 * nothing Sygnal exists and {@code /sygnal/**} answers 404.
 */
@Configuration
@ConditionalOnProperty(prefix = "receiver.beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(BecknProperties.class)
public class BecknConfig {

    public static final String CARE_ALERT_EXECUTOR = "careAlertExecutor";

    public BecknConfig(BecknProperties props) {
        // Fail at start, not at the first alert.
        props.validate();
    }

    /**
     * Raises the OpenMRS notification of a care alert after the {@code Ack}. When the queue is full the
     * task is refused and the {@code sygnal/signal/emit} is answered with a 503 {@code Nack}.
     */
    @Bean(CARE_ALERT_EXECUTOR)
    public Executor careAlertExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(4);
        ex.setQueueCapacity(200);
        ex.setThreadNamePrefix("care-alert-");
        ex.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(10);
        return ex;
    }
}
