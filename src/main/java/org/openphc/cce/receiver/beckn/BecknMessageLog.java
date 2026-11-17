package org.openphc.cce.receiver.beckn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * One log line per Beckn message: each one received and each {@code on_update} sent
 * (docs/beckn-integration.md §9.2). Never logs a message body, a FHIR resource or a patient id.
 */
public final class BecknMessageLog {

    private static final Logger LOG = LoggerFactory.getLogger("org.openphc.cce.receiver.beckn.messages");
    private static final int MAX = 200;

    private final Map<String, Object> fields = new LinkedHashMap<>();

    private BecknMessageLog(String direction, String action) {
        fields.put("direction", direction);
        fields.put("action", action);
    }

    public static BecknMessageLog in(String action) {
        return new BecknMessageLog("IN", action);
    }

    public static BecknMessageLog out(String action) {
        return new BecknMessageLog("OUT", action);
    }

    public BecknMessageLog peer(String v) { return put("peer", v); }
    public BecknMessageLog contractId(String v) { return put("contractId", v); }
    public BecknMessageLog transactionId(String v) { return put("transactionId", v); }
    public BecknMessageLog messageId(String v) { return put("messageId", v); }
    public BecknMessageLog performanceId(String v) { return put("performanceId", v); }
    public BecknMessageLog resourceType(String v) { return put("resourceType", v); }
    public BecknMessageLog referralId(String v) { return put("referralId", v); }
    public BecknMessageLog result(String v) { return put("result", v); }
    public BecknMessageLog code(String v) { return put("code", v); }
    public BecknMessageLog httpStatus(int v) { return v > 0 ? put("httpStatus", Integer.toString(v)) : this; }
    public BecknMessageLog error(String v) { return put("error", v); }

    private BecknMessageLog put(String key, String value) {
        if (value != null && !value.isBlank()) {
            fields.put(key, value);
        }
        return this;
    }

    /** Writes the line: WARN for a failure, INFO otherwise. */
    public void write() {
        StringJoiner line = new StringJoiner(" ", "beckn ", "");
        fields.forEach((k, v) -> {
            String c = clean(String.valueOf(v));
            line.add(k + "=" + (c.indexOf(' ') >= 0 ? "\"" + c + "\"" : c));
        });
        boolean failed = fields.containsKey("error") || "ERROR".equals(fields.get("result"));
        if (failed) {
            LOG.warn(line.toString());
        } else {
            LOG.info(line.toString());
        }
    }

    /** Values come from the message: no control characters, no double quotes, and cut short. */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        String v = value.replaceAll("\\p{Cntrl}", "_").replace('"', '\'');
        return v.length() > MAX ? v.substring(0, MAX) + "..." : v;
    }
}
