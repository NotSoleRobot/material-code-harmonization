package com.sih.materialmaster.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.Executor;

/**
 * Wakes the separately hosted matching worker as soon as the Spring API is
 * ready. Render may suspend both free services; starting this request in the
 * background overlaps their cold starts instead of making the first user's
 * ingestion or comparison request pay them serially.
 */
@Component
@Profile("python-matching & hosted")
public class MatchingServiceWarmup implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(MatchingServiceWarmup.class);

    private final RestClient client;
    private final Executor executor;

    public MatchingServiceWarmup(
            @Value("${matching.service.url:http://localhost:5000}") String baseUrl,
            @Qualifier("taskExecutor") Executor executor) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(10));
        requestFactory.setReadTimeout(Duration.ofSeconds(120));
        this.client = RestClient.builder()
                .baseUrl(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.executor = executor;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        executor.execute(() -> {
            for (int attempt = 1; attempt <= 4; attempt++) {
                try {
                    client.get().uri("/health").retrieve().toBodilessEntity();
                    log.info("Matching service warm-up completed");
                    return;
                } catch (RuntimeException ex) {
                    log.warn("Matching service warm-up attempt {}/4 did not complete: {}",
                            attempt, ex.getMessage());
                    if (attempt < 4) {
                        try {
                            Thread.sleep(5000L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
            log.error("Matching service did not become ready during background warm-up");
        });
    }
}
