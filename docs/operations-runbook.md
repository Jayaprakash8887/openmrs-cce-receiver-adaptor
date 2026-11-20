# Operations Runbook — OpenMRS CCE Receiver Adaptor

## Service Info

| Property | Value |
|----------|-------|
| Service | `cce-receiver-adaptor` |
| Port | `8080` (default) |
| Health | `GET /actuator/health` |
| Metrics | `GET /actuator/prometheus` |

---

## Common Operations

```bash
# Start / Stop / Restart
docker compose up -d openmrs-cce-receiver-adaptor
docker compose stop openmrs-cce-receiver-adaptor
docker compose restart openmrs-cce-receiver-adaptor

# View logs
docker compose logs -f openmrs-cce-receiver-adaptor

# View logs filtered by correlation ID
docker compose logs openmrs-cce-receiver-adaptor | grep "abc-correlation-id"

# Check health
curl -s http://localhost:8080/actuator/health | jq

# Test with a sample Patient
curl -X POST http://localhost:8080/api/v1/openmrs/fhir \
  -H "Content-Type: application/fhir+json" \
  -H "X-Correlation-ID: test-001" \
  -H "X-Source-System: manual-test" \
  -d '{"resourceType":"Patient","name":[{"family":"Test","given":["Ops"]}],"gender":"male","birthDate":"1990-01-01"}'
```

---

## Troubleshooting

### Startup Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Connection refused` to OpenMRS at startup | OpenMRS not ready | Ensure OpenMRS is healthy before starting adaptor; use `depends_on` with health check in Docker Compose |
| `No Login Location found` warning | No location with "Login Location" tag | Create a location with the "Login Location" tag in OpenMRS admin |
| `No patient identifier type matching 'OpenMRS ID'` | Missing identifier type | Create "OpenMRS ID" identifier type in OpenMRS admin |

### Processing Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Unresolvable concept` error | Missing concept source mapping in OpenMRS | Add the LOINC/SNOMED/ICD mapping to the concept dictionary |
| `422 Unprocessable Entity` from OpenMRS | Invalid payload after transformation | Check logs for full REST payload; compare with OpenMRS API docs |
| `Patient#null failed to validate` | Missing preferred identifier | Check PatientIdentifierEnricher is functioning; verify identifier type exists |
| `Encounter invisible in O3 UI` | No Visit linkage | Check VisitManager logs; verify Visit type exists in OpenMRS |
| `401 Unauthorized` from OpenMRS | Bad outbound credentials | Check `OPENMRS_AUTH_USERNAME`/`OPENMRS_AUTH_PASSWORD`; for OAuth2 verify token URL and client credentials |
| `502 Bad Gateway` in results | OpenMRS unreachable after 3 retries | Check network; verify OpenMRS is healthy |
| `Column 'uuid' cannot be null` | FHIR path missing element IDs | This should only happen on FHIR fallback path; check FhirElementIdEnricher |

### Authentication Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401` on `POST /api/v1/openmrs/fhir` | Inbound auth failure | Check `CCE_SECURITY_ENABLED`, `CCE_SECURITY_USERNAME`, `CCE_SECURITY_PASSWORD` |
| `401` on OpenMRS calls | Outbound auth failure | Check `OPENMRS_AUTH_TYPE`, credentials |
| OAuth2 token expired | Token cache stale | Restart adaptor; check `OAuth2TokenProvider` logs |

### Concept Resolution Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `No concept found for source=LOINC, code=12345-6` | Missing concept mapping | Add LOINC mapping to the concept in OpenMRS concept dictionary |
| `No concept found for source=ICD-11, code=BA00` | ICD-11 source not configured | Create "ICD-11" concept source in OpenMRS; add mappings |
| Concepts resolving to wrong UUID | Duplicate mappings | Audit concept source mappings in OpenMRS |

### SPICE-Specific Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| ServiceRequest `concept` is null / OpenMRS returns 400 | SPICE sends `code.coding` empty; category identifier not resolving | Check if the category name (e.g., "NCD") exists as a concept in OpenMRS; adaptor falls back to "Private health care clinic/facility" (CIEL 160479) |
| ServiceRequest `orderer` is invalid / OpenMRS returns 400 | SPICE sets `requester` to Organization, not Practitioner | Adaptor looks for Practitioner in `performer[]`; verify the Bundle includes a Practitioner resource referenced by the ServiceRequest |

### Standalone Order Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| Order fails with "encounter required" | Standalone ServiceRequest/MedicationRequest without encounter reference | Adaptor auto-creates encounter; check VisitManager logs for encounter creation; verify "Consultation" encounter type exists |
| Duplicate encounters created for orders | Each standalone order creates a new encounter by design | This is expected behaviour — reusing encounters would be semantically incorrect |

### Notification (POST /alert) Issues

Log source: `OpenMrsNotificationClient`, `RecipientResolver`. Notification failures NEVER fail the referral order create — search for `WARN` lines.

| Symptom | Cause | Fix |
|---------|-------|-----|
| Referral created but no toast in O3 | Notifications disabled | Confirm `OPENMRS_NOTIFICATION_ENABLED=true` |
| `Skipping notify — no recipients resolved` | Empty recipients list | Set `OPENMRS_NOTIFICATION_RECIPIENTS_STATIC_UUIDS` to a real OpenMRS user UUID for this env |
| `Failed to push alert ... 400 BAD_REQUEST ... dateToExpire ... malformed` | Date format mismatch (regression) | Ensure `dateToExpire` is formatted with `yyyy-MM-dd'T'HH:mm:ss.SSSZ` (numeric offset, not `Z`). Currently handled in `OpenMrsNotificationClient` |
| `Failed to push alert ... 400 ... recipient` | UUID is a person UUID, not a user UUID | Re-resolve via `GET /user?v=custom:(uuid,display)` |
| Toast pops for old test alerts | OpenMRS retains all unread alerts; ESM polls every 30s | Mark old alerts read in OpenMRS DB or wait for `dateToExpire` |
| Same alert fired twice | Dedupe TTL too short, or upstream retry storm | Raise `OPENMRS_NOTIFICATION_DEDUPE_TTL_HOURS` |
| `Pushed alert ... to N recipient(s)` where N is unexpectedly high | `role-or-static` policy + OpenMRS `/user?role=` returns all users (server-side filter is a no-op) | Switch `OPENMRS_NOTIFICATION_RECIPIENTS_POLICY=static` |

### Sygnal (CCE care alert) Issues

> **Status:** Built (demo), on by default on this branch. Full design: [sygnal-integration.md](sygnal-integration.md). The existing `/api/v1/openmrs/fhir` endpoint is unchanged.

Quick checks. Run the `curl` from any container on `cce-net` that has it; on the dev host, `http://localhost:8097` reaches the adaptor too.

```bash
# Endpoint is on: an empty object should answer Nack (400) CTX_MISSING_FIELD, not 404
curl -s -X POST http://openmrs-cce-receiver-adaptor:8080/sygnal/signal/emit \
  -H 'Content-Type: application/json' -d '{}'

# One log line per signal (WARN for a Nack)
docker compose logs openmrs-cce-receiver-adaptor | grep 'sygnal direction='
```

| Symptom | Cause | Fix |
|---------|-------|-----|
| `404` on `/sygnal/signal/emit` | The endpoint is off | Set `OPENMRS_RECEIVER_BECKN_ENABLED=true` and restart |
| `404` on `/beckn/bap-webhook/*` or `/beckn/bpp-webhook/*` | Those Beckn endpoints are removed | Route `sygnal/signal/emit` in `onix-openmrs` to `http://openmrs-cce-receiver-adaptor:8080` |
| Adaptor does not start: `receiver.beckn.enabled=true, but these settings are missing: …` | An id is set to blank | Set `OPENMRS_RECEIVER_BECKN_SELF_ID` / `OPENMRS_RECEIVER_BECKN_CCE_ID`, or unset them to use the defaults |
| Every alert gets `Nack` `CTX_INVALID_FIELD` "context.receiverId …" | CCE addresses OpenMRS with another subscriber id | Align `OPENMRS_RECEIVER_BECKN_SELF_ID` with the id in `cce-beckn-service`'s HMIS list and the registry |
| Every alert gets `Nack` `CTX_INVALID_FIELD` "context.senderId …" | CCE's subscriber id differs from `receiver.beckn.cce.id` | Align `OPENMRS_RECEIVER_BECKN_CCE_ID` with CCE's registered id |
| `Nack` `CTX_ACTION_MISMATCH` | onix routed another action to this path | Fix the `onix-openmrs` routing: only `sygnal/signal/emit` goes to the adaptor |
| `Nack` `SCH_TYPE_NOT_SUPPORTED` | The signal is not a `sygnal:Signal` with a `cce:CceCareAlert` | Check the sender's version against the CCE Sygnal profile |
| `401` on a signal | onix routing points at an `/api/...` path; the bearer-token filter rejects the `Authorization: Signature …` header | Route to `http://openmrs-cce-receiver-adaptor:8080` (onix appends `/sygnal/signal/emit`) |
| onix cannot resolve `openmrs-cce-receiver-adaptor` | Containers on different networks | Put the adaptor and `onix-openmrs` on `cce-net` |
| `Nack` (503) `NET_INTERNAL_ERROR` "queue is full", `openmrs_beckn_care_alerts_total{result="dropped"}` rises | More alerts than the notification threads can raise (OpenMRS slow) | Check OpenMRS health. CCE may send again |
| Alert acked but no notification; `openmrs_beckn_care_alerts_total{result="not-notified"}` rises | No recipients resolved, notifications disabled, or OpenMRS `/alert` failed | See the notification rows above (`OPENMRS_NOTIFICATION_*`) |
| Same alert notified once though CCE sent it twice | De-duplication on the delivery id | Expected |

---

## Log Format

```
2026-04-16T10:30:00.000 [http-nio-8080-exec-1] INFO  o.o.c.r.s.InboundProcessingService [corr-123] [cce-intelligence] [Patient] - Processing Patient resource
```

| Field | Source |
|-------|--------|
| `[corr-123]` | `X-Correlation-ID` header |
| `[cce-intelligence]` | `X-Source-System` header |
| `[Patient]` | Current resource type being processed |

---

## Key Metrics to Monitor

| Metric | Healthy | Investigate |
|--------|---------|-------------|
| `cce_receiver_requests_received_total` | Increasing (when traffic expected) | Flat (no inbound traffic) |
| `cce_receiver_resources_routed_total{status="success"}` | Matches received entries | Diverging from received |
| `cce_receiver_resources_routed_total{status="failure"}` | 0 or very low | Any sustained increase |
| `cce_receiver_rest_latency_seconds` | < 5s (p95) | > 10s |
| `cce_receiver_transform_errors_total` | 0 | Any increase |
| `cce_receiver_processing_total_seconds` | < 30s per Bundle | > 60s |
| `openmrs_beckn_messages_received_total{action="sygnal/signal/emit",result="nack"}` | 0 | Any sustained increase |
| `openmrs_beckn_care_alerts_total{result!="notified"}` | 0 or very low | Any sustained increase |

---

## Recommended Alerts

```yaml
groups:
  - name: openmrs-cce-receiver
    rules:
      - alert: CceReceiverDown
        expr: up{job="cce-receiver-adaptor"} == 0
        for: 1m
        labels: { severity: critical }
        annotations:
          summary: "CCE Receiver Adaptor is down"

      - alert: CceReceiverHighFailureRate
        expr: >
          rate(cce_receiver_resources_routed_total{status="failure"}[5m])
          / rate(cce_receiver_resources_routed_total[5m]) > 0.1
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "More than 10% of resources failing"

      - alert: CceReceiverHighLatency
        expr: histogram_quantile(0.95, rate(cce_receiver_rest_latency_seconds_bucket[5m])) > 10
        for: 5m
        labels: { severity: warning }
        annotations:
          summary: "REST call p95 latency exceeds 10 seconds"

      - alert: CceReceiverTransformErrors
        expr: increase(cce_receiver_transform_errors_total[5m]) > 5
        for: 2m
        labels: { severity: warning }
        annotations:
          summary: "Transformation errors increasing"

      - alert: CceReceiverOpenMrsUnreachable
        expr: increase(cce_receiver_resources_routed_total{status="failure"}[5m]) > 20
        for: 2m
        labels: { severity: critical }
        annotations:
          summary: "High failure rate — OpenMRS may be unreachable"
```

---

## Operational Procedures

### Force Re-Discovery of OpenMRS Config

Restart the adaptor — auto-discovery runs on `ApplicationReadyEvent`:

```bash
docker compose restart openmrs-cce-receiver-adaptor
```

Or wait for the next capability refresh cycle (default: 6 hours, configurable via `OPENMRS_CAPABILITY_REFRESH_MS`).

### Add a New Identifier Type

When a new source system sends identifiers with an unknown `system`:
1. The adaptor **auto-creates** the identifier type in OpenMRS (no manual action needed)
2. If auto-creation fails, manually create the identifier type in OpenMRS admin

### Add a New Concept Source Mapping

When a new code system or code is not resolving:
1. Check adaptor logs for the exact `system` URI and `code`
2. In OpenMRS admin → Concept Dictionary:
   - Ensure the concept source exists (e.g., "ICD-11")
   - Add the mapping to the relevant concept
3. The adaptor resolves concepts live from OpenMRS on every request — the new mapping will be picked up immediately on the next request (no restart needed)

---

## Maintenance

- **Stateless service** — no persistent data to back up
- **No in-memory caches** — all concept and reference lookups are resolved live from OpenMRS on every request
- **No database dependency** — the adaptor itself has no database
- **Upgrading:** Build new image → restart container with same environment variables
- **Scaling:** Stateless design supports horizontal scaling behind a load balancer
