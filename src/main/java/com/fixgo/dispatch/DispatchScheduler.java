package com.fixgo.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fixgo.dispatch.scheduler-enabled", havingValue = "true", matchIfMissing = true)
public class DispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(DispatchScheduler.class);
    private final DispatchService dispatch;

    public DispatchScheduler(DispatchService dispatch) { this.dispatch = dispatch; }

    @Scheduled(fixedDelayString = "${fixgo.dispatch.scheduler-interval-ms:5000}")
    public void run() {
        try {
            dispatch.tick();
        } catch (RuntimeException ex) {
            log.error("Dispatch tick failed", ex);
        }
    }
}
