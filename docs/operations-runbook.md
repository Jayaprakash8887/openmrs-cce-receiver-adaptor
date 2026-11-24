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

### Beckn Issues

> **Status:** Built (demo), off by default. Full design: [beckn-integration.md](beckn-integration.md). The existing `/api/v1/openmrs/fhir` endpoint is unchanged.

Quick checks. Run the `curl` from any container on `cce-net` that has it; on the dev host, `http://localhost:8097` reaches the adaptor too. Run the `grep` in the `openmrs-onix` checkout.

```bash
# Endpoint is on: an unsupported action should answer Nack (400) NOT_SUPPORTED, not 404
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  http://openmrs-cce-receiver-adaptor:8080/beckn/bpp-webhook/cancel \
  -H 'Content-Type: application/json' -d '{}'

# Where onix sends inbound messages
grep -A3 target devkit/config/local-simple-routing-BPPReceiver.yaml devkit/config/local-simple-routing-BAPReceiver.yaml

# One log line per Beckn message
docker compose logs openmrs-cce-receiver-adaptor | grep -i beckn
```

| Symptom | Cause | Fix |
|---------|-------|-----|
| `404` on `/beckn/bpp-webhook/*` or `/beckn/bap-webhook/*` | Beckn is off | Set `OPENMRS_RECEIVER_BECKN_ENABLED=true` and restart |
| Adaptor does not start: `receiver.beckn.enabled=true, but these settings are missing: …` | Beckn is on but an id is blank | Set the named `BECKN_*` variables (see the Configuration Guide) |
| `beckn direction=OUT action=on_update … result=ERROR` | `onix-bpp` caller unreachable or refused the `on_update` | Check `OPENMRS_RECEIVER_BECKN_ONIX_BPP_CALLER_URL` and `onix-bpp`; Spice already has its `Ack`, nothing is lost |
| `401` on a Beckn message | onix routing points at an `/api/...` path; the bearer-token filter rejects the `Authorization: Signature …` header | Point the routing at `/beckn/bpp-webhook` and `/beckn/bap-webhook`, not `/api/...` |
| Spice's referrals never reach the adaptor; onix logs connection errors to `host.docker.internal:8075` | `openmrs-onix` routing is still the `cce-onix` copy | Set `target.url` to `http://openmrs-cce-receiver-adaptor:8080/beckn/bpp-webhook` (BPPReceiver) and `.../beckn/bap-webhook` (BAPReceiver) |
| onix cannot resolve `openmrs-cce-receiver-adaptor` | Containers on different networks | Put the adaptor and both onix containers on `cce-net` |
| onix containers fail to start on the dev host | Host ports 8081 / 8082 taken by `keycloak` / `cce-openhim-core` | Publish onix on 8181 / 8182 |
| Every referral gets `Nack` `BAD_REQUEST`, `openmrs_beckn_peer_unknown_total` rises | Spice's `bapId` differs from `receiver.beckn.spice.bap-id` | Align `OPENMRS_RECEIVER_BECKN_SPICE_BAP_ID` with Spice's registered subscriber id |
| `Nack` `REFERRAL_REJECTED` with a write-path reason | The referral failed as it would on `/api/v1/openmrs/fhir` (400, 422, or 207 without the `ServiceRequest`) | Use the Processing and SPICE-specific rows above |
| Spice gets `Nack` (500) `UNAVAILABLE` | OpenMRS slow or down (write path answered 503, 500 or 502), or an exception | Check OpenMRS health; Spice sends again |
| Spice or `spice-onix` times out, but the referral is written in OpenMRS | `onix-bpp`'s wait is shorter than the write path (onix default 5 s) | Raise `onix-bpp`'s `responseHeaderTimeout` above the write path's worst case. Spice's resend is caught as a repeat |
| Referral written, but Spice never sees `ACCEPTED` | `on_update` to `onix-bpp` failed. It is sent once | Check `OPENMRS_RECEIVER_BECKN_ONIX_BPP_CALLER_URL` and `openmrs_beckn_on_update_sent_total{result!="ack"}`. The `Ack` is the proof; Spice should not depend on `on_update` |
| No `on_update` logged for an event the emitter sent, or it is logged before the emitter's `Ack` | `on_update` is sent once and may come before the `Ack` | Expected. Only the `Ack` counts |
| Referral shows a placeholder patient | Spice sent no `Patient` entry | Ask Spice to send the `Patient` as a second performance entry |
| `cancel` answered `Nack` `NOT_SUPPORTED` | Not supported in the demo | Expected |
| A health referral (`select` …) never reaches the adaptor; onix logs `failed to load schema from https://schema.beckn.io/Health…` | The adapter has no local copy of the Health* schemas | Redeploy with deploy-scripts that ship `k8s/beckn/onix/health-schema/`; check `kubectl get cm onix-schema` lists `HealthReferral__v2.1__attributes.yaml` |
| Spice's `update` or callback refused by onix with `SCH_SCHEMA_ADAPTATION_FAILED`; nothing reaches the adaptor | One side still sends the old `cce:CceClinicalEvent` box, which an adapter without the old schema refuses | Since 2026-11-23 the box is a `sygnal:CoordinationEvent` with an `@context`. Spice and OpenMRS change their boxes and their referral adapters (`CoordinationEvent/v3.0` plus the Health* pack) at the same time |
| `confirm` answered `BAD_REQUEST` "no participant with participantRole PATIENT" or "no … healthIds" | Spice sent no patient, or a patient without a health id | Ask Spice to send the `PATIENT` participant with at least one `healthIds` entry |
| `status` answered `Ack` but no `on_status` | OpenMRS has no order for that coordination id (`openmrs_beckn_status_unknown_total`): never confirmed, or the adaptor restarted and the request carries no patient health id | Expected; Spice can send `status` with the participants |
| Health-referral patient gets a new identifier type named after the system (e.g. `ABHA`) | No `OPENMRS_RECEIVER_BECKN_HEALTH_ID_TYPES` pair for it | Set e.g. `ABHA=national-id` if that id should be the national id |
| Outcome of a referral out not visible in OpenMRS | `on_status` from Spice is logged only | Expected in the demo (design Q5) |

### Sygnal (CCE care alert) Issues

> Full design: [sygnal-integration.md](sygnal-integration.md).

```bash
# Endpoint is on: an empty object should answer Nack (400) CTX_MISSING_FIELD, not 404
curl -s -X POST http://openmrs-cce-receiver-adaptor:8080/sygnal/signal/emit \
  -H 'Content-Type: application/json' -d '{}'

# One log line per signal (WARN for a Nack)
docker compose logs openmrs-cce-receiver-adaptor | grep 'sygnal direction='
```

| Symptom | Cause | Fix |
|---------|-------|-----|
| `404` on `/sygnal/signal/emit` | The endpoints are off | Set `OPENMRS_RECEIVER_BECKN_ENABLED=true` and restart |
| Every alert gets `Nack` `CTX_INVALID_FIELD` "context.receiverId …" | CCE addresses OpenMRS with another subscriber id | Align `OPENMRS_RECEIVER_BECKN_SELF_ID` with `cce-beckn-service`'s HMIS list and the registry |
| Every alert gets `Nack` `CTX_INVALID_FIELD` "context.senderId …" | CCE's subscriber id differs from `receiver.beckn.cce.id` | Align `OPENMRS_RECEIVER_BECKN_CCE_ID` with CCE's registered id |
| `Nack` `CTX_ACTION_MISMATCH` | onix routed another action to this path | Only `sygnal/signal/emit` goes to the adaptor from `/sygnal/receiver/` |
| `Nack` `SCH_TYPE_NOT_SUPPORTED` "message.@type must be sygnal:Signal" | The `message` is not a `sygnal:Signal`. Most likely the sender still sends the `cce:CceCareAlert` message of profile v2.0 | Upgrade `cce-beckn-service` to the CCE Sygnal profile v3.0 |
| `Nack` `SCH_REQUIRED_FIELD_MISSING` "message.patientId is missing" | The Signal has no care-alert fields of its own. Most likely the sender still puts the alert in `signalAttributes` (profile v1.0) | Upgrade `cce-beckn-service` to the CCE Sygnal profile v3.0 |
| `onix-openmrs` refuses alerts on `severity` or `cceSeverity`; nothing reaches the adaptor | Since 2026-11-24 the profile requires CCE's level in `cceSeverity`, and `severity`, when present, takes only Sygnal's levels (`INFO`, `WARNING`, `CRITICAL`). The sender is a `cce-beckn-service` built before that, which puts CCE's level (`HIGH`, …) in `severity` or in a `signalAttributes` bag | Upgrade `cce-beckn-service` together with the profile files in every onix |
| Every alert shows as `INFO` in OpenMRS | The adaptor is a build from before 2026-11-24: it reads `message.severity` or the `signalAttributes` bag, which CCE no longer sends, so it finds no level | Upgrade the adaptor: it reads CCE's level from `cceSeverity` |
| CCE alerts arrive as `on_status` on `/beckn/bap-webhook` and nothing is notified | CCE still on the old Beckn version | Upgrade `cce-beckn-service`; alerts must come as `sygnal/signal/emit` |
| `Nack` (503) `NET_INTERNAL_ERROR` "queue is full", `openmrs_beckn_care_alerts_total{result="dropped"}` rises | More alerts than the notification threads can raise (OpenMRS slow) | Check OpenMRS health. CCE may send again |
| Alert acked but no notification; `openmrs_beckn_care_alerts_total{result="not-notified"}` rises | No recipients, notifications disabled, or OpenMRS `/alert` failed | See the notification rows above (`OPENMRS_NOTIFICATION_*`) |

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
| `openmrs_beckn_messages_received_total{action="update",result="nack"}` (proposal) | 0 | Any sustained increase |
| `openmrs_beckn_peer_unknown_total` (proposal) | 0 | Any |
| `openmrs_beckn_on_update_sent_total{result!="ack"}` (proposal) | 0 | Any |
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
