# API Reference — OpenMRS CCE Receiver Adaptor

## Overview

The CCE Receiver Adaptor exposes a single inbound **FHIR R4 endpoint** for receiving clinical data from the **CCE Core Intelligence Service**. It also exposes standard **Spring Boot Actuator** endpoints for health and monitoring. The Intelligence Service produces FHIR R4 resources as action outputs and POSTs them to this endpoint.

---

## Inbound Endpoints

### POST /api/v1/openmrs/fhir

Receives a FHIR R4 Bundle or standalone resource, processes it through the enrichment and transformation pipeline, and routes each resource to OpenMRS.

| | |
|---|---|
| **Method** | `POST` |
| **URL** | `/api/v1/openmrs/fhir` |
| **Content-Type** | `application/fhir+json` or `application/json` |
| **Auth** | Depends on `cce.security.enabled` (none / Basic / JWT) |

#### Request Headers

| Header | Required | Description |
|--------|----------|-------------|
| `Content-Type` | Yes | `application/fhir+json` or `application/json` |
| `X-Correlation-ID` | No | Correlation ID for request tracing (propagated to logs) |
| `X-Source-System` | No | Source system identifier (e.g., `cce-intelligence`, `RHIE`, `SPICE`) |
| `X-CCE-Intelligence-Delivery-Id` | No | Per-delivery identifier emitted by `cce-intelligence-service`; logged for end-to-end tracing across the two services |
| `X-CCE-Intelligence-Event-Id` | No | Originating intelligence-event identifier; logged for end-to-end tracing |
| `Authorization` | Conditional | Required when `cce.security.enabled=true` |

#### Request Body

FHIR R4 Bundle (transaction or batch):

```json
{
  "resourceType": "Bundle",
  "type": "transaction",
  "entry": [
    {
      "fullUrl": "urn:uuid:patient-1",
      "resource": {
        "resourceType": "Patient",
        "name": [{"family": "Doe", "given": ["John"]}],
        "gender": "male",
        "birthDate": "1990-01-15",
        "identifier": [
          {"system": "NID", "value": "1199080012345678"}
        ]
      },
      "request": {
        "method": "POST",
        "url": "Patient"
      }
    },
    {
      "fullUrl": "urn:uuid:encounter-1",
      "resource": {
        "resourceType": "Encounter",
        "status": "finished",
        "type": [{"coding": [{"display": "CONSULTATION_ENCOUNTER"}]}],
        "subject": {"reference": "urn:uuid:patient-1"},
        "period": {"start": "2026-04-16T09:00:00Z"}
      },
      "request": {
        "method": "POST",
        "url": "Encounter"
      }
    }
  ]
}
```

Or a standalone FHIR resource:

```json
{
  "resourceType": "Patient",
  "name": [{"family": "Doe", "given": ["John"]}],
  "gender": "male",
  "birthDate": "1990-01-15"
}
```

#### Response — Success (All Resources)

**Status:** `202 Accepted`

```json
{
  "timestamp": "2026-04-16T10:30:00.000Z",
  "totalEntries": 2,
  "succeeded": 2,
  "failed": 0,
  "results": [
    {
      "resourceType": "Patient",
      "resourceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
      "route": "rest",
      "status": "created",
      "httpStatus": 201
    },
    {
      "resourceType": "Encounter",
      "resourceId": "f0e1d2c3-b4a5-6789-0123-456789abcdef",
      "route": "rest",
      "status": "created",
      "httpStatus": 201
    }
  ]
}
```

#### Response — Partial Failure

**Status:** `207 Multi-Status`

```json
{
  "timestamp": "2026-04-16T10:30:00.000Z",
  "totalEntries": 3,
  "succeeded": 2,
  "failed": 1,
  "results": [
    {
      "resourceType": "Patient",
      "resourceId": "a1b2c3d4-...",
      "route": "rest",
      "status": "created",
      "httpStatus": 201
    },
    {
      "resourceType": "Observation",
      "resourceId": null,
      "route": "rest",
      "status": "failed",
      "httpStatus": 400,
      "errorMessage": "Unresolvable concept: system=http://loinc.org, code=UNKNOWN-CODE"
    },
    {
      "resourceType": "Encounter",
      "resourceId": "f0e1d2c3-...",
      "route": "rest",
      "status": "created",
      "httpStatus": 201
    }
  ]
}
```

#### Response Status Code Matrix

The adaptor returns one of the following status codes based on the per-resource processing outcome. This matrix is the contract that `cce-intelligence-service` uses to decide whether to retry a delivery.

| Status | Condition | Caller action |
|--------|-----------|---------------|
| `202 Accepted` | All resources in the bundle processed successfully | Mark delivery complete |
| `207 Multi-Status` | Bundle partially succeeded — at least one resource succeeded **and** at least one failed | Inspect `results[]`; do **not** retry the whole delivery |
| `422 Unprocessable Entity` | All resources failed and **none** of the failures look transient (validation / mapping / 4xx from OpenMRS) | Do **not** retry; surface to operator |
| `503 Service Unavailable` | All resources failed and **at least one** failure is transient (HTTP 5xx, 408, 429, or status `0` with a transport-error message such as `timeout`, `unavailable`, `unreachable`, `refused`, `connect`) | Retry with exponential backoff |
| `400 Bad Request` | Body is not valid JSON / not parseable as a FHIR resource | Do not retry; fix payload |
| `401 Unauthorized` | Missing or invalid credentials (when `cce.security.enabled=true`) | Re-auth and retry |

> **Retryable-failure classification.** A failure is treated as retryable only when the underlying call indicates a transient server-side or network problem. Definitive client errors (4xx with status, validation rejections from OpenMRS) are **not** retried, even if their stack-trace bodies happen to mention transport-level keywords. See `ProcessingStatusResolver#isRetryableFailure` for the exact rules (shared with the Beckn endpoint).

---

## Beckn Endpoints

> **Status:** Built (demo), off by default. Full design, samples and settings: [beckn-integration.md](beckn-integration.md).

Two endpoints, present only when `OPENMRS_RECEIVER_BECKN_ENABLED=true` (`receiver.beckn.enabled`). With it off they answer 404. They are called by the `openmrs-onix` adapter containers, which have already checked the Beckn signature and the schema. No bearer token is checked: the paths are outside the `/api/**` filter. The body is a full Beckn message; each performance entry carries one FHIR resource in a `sygnal:CoordinationEvent` box with an `@context` (schema `CoordinationEvent/v3.0`; until 2026-11-23 a `cce:CceClinicalEvent` box). The adaptor reads only `performanceAttributes.resource`.

The existing `POST /api/v1/openmrs/fhir` endpoint is unchanged.

Both endpoints answer in the same `Ack` / `Nack` body as `cce-beckn-service`. Both keep the request's `context.messageId` (as `cce-beckn-service` does, a `messageId` that is not a UUID is replaced by a new one).

```json
{ "message": { "status": "ACK", "messageId": "c7d8e9f0-1a2b-4c3d-9e4f-5a6b7c8d9e0f" } }
```

```json
{ "message": { "status": "NACK", "messageId": "c7d8e9f0-1a2b-4c3d-9e4f-5a6b7c8d9e0f",
  "error": { "code": "REFERRAL_REJECTED", "message": "ServiceRequest/1838: patient could not be resolved" } } }
```

| Code | HTTP | When |
|---|---|---|
| `BAD_REQUEST` | 400 | A rule is broken: body not JSON, `context.action` not the path's action, wrong contract id, a performance entry without a `resource`, no `ServiceRequest`, more than two entries, a resource other than `ServiceRequest` / `Patient`, a missing field |
| `NOT_SUPPORTED` | 400 | Any other action |
| `REFERRAL_REJECTED` | 400 | The write path refused the referral (400, 422, or 207 without the `ServiceRequest`). The text carries the reason |
| `UNAVAILABLE` | 500 | The write path answered 503, 500 or 502, OpenMRS did not answer, an exception, or 207 where the `ServiceRequest` failed for a transient reason. Send again later |

Beckn uses HTTP 500 for "send again later", to match `cce-beckn-service`. `/api/v1/openmrs/fhir` keeps its 503.

### POST /beckn/bpp-webhook/{action}

Called by `onix-bpp`. Referrals from Spice: `update` with FHIR resources, or the health-referral flow (`select` / `init` / `confirm` / `status`, a `hrf:HealthReferral` contract; [beckn-integration.md §4A](beckn-integration.md)).

| `action` | What the adaptor does | Answer |
|---|---|---|
| `update` | A referral. One performance entry for the `ServiceRequest`, and an optional second one for the `Patient` (allowed here because this leg never reaches CCE). Checks the contract id, builds a FHIR `Bundle` (type `transaction`, `Patient` first) from every `performanceAttributes.resource`, and writes it through the same path as `/api/v1/openmrs/fhir` | See below |
| `select`, `init` | A health referral being agreed. Nothing is written | `Ack`, then `on_select` / `on_init` (`DRAFT`), echoing the contract |
| `confirm` | A health referral: the `PATIENT` participant and the `HealthReferral` become a `Patient` + referral `ServiceRequest` `Bundle`, written through the same path. The coordination id is the order's accession number | As for `update` (below), then `on_confirm` (`ACTIVE`) instead of `on_update` |
| `status` | Reads the order of a health referral | `Ack`, then `on_status` with the order's state (`ACTIVE`, `COMPLETE`/`CLOSED`, `CANCELLED`); none when there is no order |
| `cancel`, `track`, `rate`, `support` | Nothing. Not supported | `Nack` (400), `NOT_SUPPORTED` |

| Write path result | Answer to `onix-bpp` |
|---|---|
| 202, or 207 with the `ServiceRequest` written | `Ack`, then `on_update` with performance status `ACCEPTED` through `onix-bpp`'s `/bpp/caller/on_update`. It keeps the request's `messageId` and `networkId`, and Spice's performance id |
| Unknown `bapId`, missing contract id, an entry without a `resource`, no `ServiceRequest`, more than two entries | `Nack` (400), `BAD_REQUEST` (the write path is not called) |
| 400, 422, or 207 without the `ServiceRequest` | `Nack` (400), `REFERRAL_REJECTED`, with the reason |
| 207 where the `ServiceRequest` failed for a transient reason (5xx, 408, 429, timeout, connection) | `Nack` (500), `UNAVAILABLE` |
| 503, 500 or 502, OpenMRS does not answer, or an exception | `Nack` (500), `UNAVAILABLE` |
| The same referral again (same accession number) | The same as the first time |

### POST /beckn/bap-webhook/{action}

Called by `onix-bap`. Callbacks for messages the emitter sent.

| `action` | From | What the adaptor does | Answer |
|---|---|---|---|
| `on_update` | OpenHIM's adaptor, or Spice | Logs `ACCEPTED` or `DUPLICATE`. It may arrive before the emitter's `Ack`, or never; nothing depends on it | `Ack` |
| `on_status` | Spice | Logs the outcome of a referral OpenMRS sent out. Nothing is written into OpenMRS yet | `Ack` |
| any other | — | Logs it | `Nack` (400), `NOT_SUPPORTED` |

---

## Sygnal Endpoint

> **Status:** Built (demo), on by default on this branch. Full design, samples and settings: [sygnal-integration.md](sygnal-integration.md).

### POST /sygnal/signal/emit

A CCE care alert, sent by `cce-beckn-service` as a Sygnal `sygnal/signal/emit` and forwarded by `onix-openmrs` (which has already checked the signature and the schema). Present only when `OPENMRS_RECEIVER_BECKN_ENABLED=true`; with it off it answers 404. No bearer token is checked: the path is outside the `/api/**` filter. CCE care alerts no longer arrive as `on_status` on `/beckn/bap-webhook`.

The body is a full Sygnal message: `context` with `action` `sygnal/signal/emit`, `senderId` = `receiver.beckn.cce.id`, `receiverId` = `receiver.beckn.self.id`; `message` is a `sygnal:Signal` with the care-alert fields directly on it (`@type`, `id` = the delivery id = `context.messageId`, `signalType`, `target`, `basis`, `issuedAt`, `cceSeverity` = CCE's `LOW` / `MEDIUM` / `HIGH` / `CRITICAL`, `patientId`, `stepStatus`, optional `slaStatus`, `fhirVersion`, `resource` = FHIR `CommunicationRequest` or `Task`). This is CCE's Sygnal profile v3.0, in use since 2026-11-23; since 2026-11-24 CCE's own level is only in `cceSeverity`, and CCE does not send Sygnal's `severity` (Sygnal does not require it; if another sender includes it, it must be one of Sygnal's `INFO` / `WARNING` / `CRITICAL`). The notification severity comes only from `cceSeverity` (`LOW` → `INFO`, `MEDIUM` / `HIGH` → `WARNING`, `CRITICAL` → `CRITICAL`); `message.severity` and a `signalAttributes` bag are not read. Without `cceSeverity` it is `INFO`. A `cce:CceCareAlert` message (profile v2.0) and a Signal with the alert only in a `signalAttributes` bag (profile v1.0) are refused. Unknown extra fields are ignored.

The answer is synchronous (Sygnal has no callbacks) and keeps the request's `context.messageId`:

```json
{ "message": { "status": "ACK", "messageId": "b2c3d4e5-0001-4000-b000-000000000001" } }
```

```json
{ "message": { "status": "NACK", "messageId": "b2c3d4e5-0001-4000-b000-000000000001",
  "error": { "code": "CTX_INVALID_FIELD", "message": "context.senderId is 'x', but care alerts are taken only from 'bpp.cce.nextsteps.mdtlabs.org'" } } }
```

| HTTP | Code | When |
|---|---|---|
| 200 | — (`ACK`) | Accepted. The OpenMRS notification is raised afterwards; a repeat (same delivery id) is acked and not notified twice |
| 400 | `SCH_INVALID_JSON` | The body is not a JSON object |
| 400 | `CTX_MISSING_FIELD` | `context`, `action`, `senderId`, `receiverId` or `messageId` missing |
| 400 | `CTX_ACTION_MISMATCH` | `context.action` is not `sygnal/signal/emit` |
| 400 | `CTX_INVALID_FIELD` | `receiverId` is not OpenMRS's id, or `senderId` is not CCE's |
| 400 | `SCH_TYPE_NOT_SUPPORTED` | `message.@type` is not `sygnal:Signal` (the `cce:CceCareAlert` of profile v2.0 included) |
| 400 | `SCH_REQUIRED_FIELD_MISSING` | `message`, `message.id`, `message.patientId` or `message.resource.resourceType` missing (a Signal with the alert only in `signalAttributes` has no `patientId`) |
| 503 | `NET_INTERNAL_ERROR` | The notification queue is full. Send again later |
| 500 | `NET_INTERNAL_ERROR` | Unexpected error. Send again later |

---

## Actuator Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/actuator/health` | Overall health status |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe |
| GET | `/actuator/prometheus` | Prometheus metrics |
| GET | `/actuator/metrics` | Metrics (JSON) |
| GET | `/actuator/info` | Application info |

---

## Custom Metrics

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `cce.receiver.requests.received` | Counter | `source` | Inbound requests received |
| `cce.receiver.resources.routed` | Counter | `resourceType`, `route`, `status` | Resources routed to REST/FHIR |
| `cce.receiver.processing.total` | Timer | — | End-to-end processing duration |
| `cce.receiver.rest.latency` | Timer | `resourceType`, `method` | REST outbound call latency |
| `cce.receiver.fhir.latency` | Timer | `resourceType`, `method` | FHIR outbound call latency |
| `cce.receiver.transform.errors` | Counter | `resourceType` | Transformation failures |

Beckn metrics (prefix `openmrs_beckn_`): `messages_received_total`, `callback_status_total`, `actions_unsupported_total`, `peer_unknown_total`, `on_update_sent_total`, and for CCE care alerts on the Sygnal endpoint `care_alerts_total` ([sygnal-integration.md §8.1](sygnal-integration.md#81-metrics)). Labels and meaning are in [beckn-integration.md §9.1](beckn-integration.md#91-metrics).

---

## OpenMRS APIs Consumed

### REST v1 API (`/ws/rest/v1`) — Primary

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/patient` | Create patient |
| POST | `/encounter` | Create encounter |
| POST | `/obs` | Create observation |
| POST | `/condition` | Create condition |
| POST | `/allergy` | Create allergy |
| POST | `/order` | Create test order or drug order |
| POST | `/alert` | Push user-facing notification (fire-and-forget, after a referral order create) |
| POST | `/visit` | Create visit |
| POST | `/provider` | Create provider |
| POST | `/location` | Create location |
| POST | `/drug` | Create drug |
| POST | `/obs` | Create immunization (as obs group) |
| POST | `/patientidentifiertype` | Create identifier type (on-demand) |
| GET | `/patient?identifier={id}` | Search patient by identifier |
| GET | `/provider?q={id}` | Search provider |
| GET | `/location?q={id}` | Search location |
| GET | `/location?tag=Login+Location` | Discover login locations |
| GET | `/concept?source={src}&code={code}` | Resolve concept |
| GET | `/patientidentifiertype` | Discover identifier types |
| GET | `/encountertype` | Discover encounter types |
| GET | `/visittype` | Discover visit types |
| GET | `/visit?patient={uuid}&includeInactive=false` | Find active visits |
| GET | `/idgen/identifiersource` | Discover identifier sources |
| GET | `/personattributetype` | Discover person attribute types |
| GET | `/user?role={role}&v=custom:(uuid)` | Resolve notification recipients (role-based policy). Server-side role filter is currently a no-op — see [openmrs-notification-integration.md](openmrs-notification-integration.md). |

### FHIR R4 API (`/ws/fhir2/R4`) — Fallback

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/Procedure` | Create procedure |
| POST | `/DiagnosticReport` | Create diagnostic report |
| POST | `/MedicationDispense` | Create medication dispense |
| POST | `/MedicationAdministration` | Create medication administration |
| POST | `/Task` | Create task |
| POST | `/Consent` | Create consent |
| PUT | `/{ResourceType}/{id}` | Update resource (fallback path) |

---

## Supported FHIR Resource Types

| Resource Type | Route | Notes |
|---|---|---|
| Patient | **REST** `/patient` | Full identifier enrichment, LuhnMod30 |
| Encounter | **REST** `/encounter` | Auto-links to active Visit or creates new one |
| Observation | **REST** `/obs` | Blood pressure component → grouped obs |
| Condition | **REST** `/condition` | ICD-11 → CIEL concept resolution |
| AllergyIntolerance | **REST** `/allergy` | NPC/SNOMED allergen mapping |
| ServiceRequest | **REST** `/order` | → `testorder` |
| MedicationRequest | **REST** `/order` | → `drugorder` with dosage mapping |
| Location | **REST** `/location` | Direct mapping |
| Practitioner | **REST** `/provider` | Direct mapping |
| Medication | **REST** `/drug` | Direct mapping |
| Immunization | **REST** `/obs` | Modelled as obs group in OpenMRS; transformed to REST obs payload |
| Procedure | **FHIR** `/Procedure` | No REST endpoint; stored as obs by fhir2 module |
| DiagnosticReport | **FHIR** `/DiagnosticReport` | No REST endpoint; stored as Encounter + grouped Obs |
| MedicationDispense | **FHIR** `/MedicationDispense` | No REST endpoint; requires fhir2 dispensing |
| MedicationAdministration | **FHIR** `/MedicationAdministration` | No REST endpoint; requires fhir2 module |
| Task | **FHIR** `/Task` | FHIR-native `fhir_task` table |
| Consent | **FHIR** `/Consent` | No REST endpoint; requires fhir2 module |
