package org.openphc.cce.receiver.beckn;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The bean exists only when {@code receiver.beckn.enabled=true} (the Sygnal endpoint is on). */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(prefix = "receiver.beckn", name = "enabled", havingValue = "true", matchIfMissing = true)
public @interface ConditionalOnBecknEnabled {
}
