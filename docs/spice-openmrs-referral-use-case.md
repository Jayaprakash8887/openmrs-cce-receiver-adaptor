# SPICE ↔ OpenMRS Referral Use Case — Architecture & Flow Overview

> **Document Purpose:** Comprehensive architecture and flow documentation for the bi-directional referral use case connecting SPICE 2.0 (community health) with OpenMRS O3 (facility clinic). This is the first of two documents. See [`referral-use-case-api-walkthrough.md`](./referral-use-case-api-walkthrough.md) for step-by-step API request/response examples.

---

## 1. System Context

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                               SPICE 2.0 (Community)                             │
│                                                                                 │
│  CHW Mobile App ──► spice-service (8087) ──► fhir-mapper (8091) ──► HAPI FHIR  │
│                                                                        (8090)   │
└──────────────────────────────────────────────────────────────────────────────┬──┘
                                                                               │
                          FHIR ServiceRequest (referral)                       │
                          via HAPI FHIR emitter polling                        │
                                                                               ▼
┌──────────────────────┐       ┌────────────────────────┐       ┌─────────────────────────────┐
│   OpenMRS Emitter    │◄──────│  HAPI FHIR JPA (8090)  │       │  OpenMRS O3 (9096)          │
│   Adaptor (Polls     │       │  (Shared FHIR server)  │       │  - REST v1 API              │
│   FHIR every 30s)   │       └────────────────────────┘       │  - FHIR2 R4 API             │
└──────────┬───────────┘                                        │  - O3 Patient Chart         │
           │                                                    └──────────────┬──────────────┘
           │  POST FHIR JSON                                                   │
           ▼                                                                   │
┌──────────────────────┐       ┌────────────────────────────────────────────┐  │
│   OpenHIM Mediator   │──────►│ openmrs-cce-receiver-adaptor (8888)        │──┘
│   (CloudEvents)      │       │  - FHIR-first or REST-first routing        │
└──────────────────────┘       │  - ServiceRequest → testorder (REST)       │
                               └────────────────────────────────────────────┘
                                                    │
                              Reverse: Encounter + Condition + MedicationRequest
                              polled by OpenMRS Emitter → forwarded to SPICE receiver
```

---

## 2. Patient Identity Model

Both SPICE and OpenMRS operate with their own internal IDs. Patient matching between systems is done via a **shared external identifier** (National ID or Virtual ID).

| System | Internal ID Type | External/Shared ID |
|--------|-----------------|-------------------|
| SPICE | `patientId` (SPICE DB primary key) | `nationalId` (NID) or `virtualId` (SPICE-generated) |
| OpenMRS | `uuid` (OpenMRS DB UUID) | Patient identifier of type `NID` or custom type |
| FHIR (HAPI) | FHIR `id` (server-assigned) | `identifier[system=.../identity-value].value` = NID |

### Key Principle

> **Neither system uses the other's internal ID.** Patient resolution happens via NID or SPICE virtual ID stored as a secondary identifier in both HAPI FHIR and OpenMRS.

```
SPICE Patient (patientId=12345, nationalId="ET/ADM/2023/001234")
       ↕  matched by nationalId
HAPI FHIR Patient (id="fhir-1a2b3c", identifier[].value="ET/ADM/2023/001234")
       ↕  matched by identifier
OpenMRS Patient (uuid="abc-def-...", identifier[type=NID].value="ET/ADM/2023/001234")
```

---

## 3. Referral Flow Overview

### 3.1 Forward Flow — SPICE → OpenMRS (Referral)

```
[Step 1] CHW conducts assessment in SPICE mobile app
         → Triggers POST /assessment/create (with referral flag)

[Step 2] spice-service processes assessment
         → Calls POST /patient/referral-tickets/create (fhir-mapper Feign client)

[Step 3] fhir-mapper creates FHIR ServiceRequest (status=active)
         → Stores in HAPI FHIR server (POST /fhir/ServiceRequest)
         → ServiceRequest.subject = "Patient/<fhirId>" (SPICE patient)
         → ServiceRequest.performer includes referred facility Organization

[Step 4] OpenMRS Emitter Adaptor polls HAPI FHIR
         → GET /ServiceRequest?_lastUpdated=gt{lastCheckpoint}&_count=50
         → Detects new ServiceRequest
         → Forwards raw FHIR JSON to OpenHIM

[Step 5] OpenHIM wraps in CloudEvents → routes to openmrs-cce-receiver-adaptor
         → POST /api/v1/fhir (FHIR ServiceRequest JSON)

[Step 6] Receiver Adaptor processes ServiceRequest
         → Routes to REST path (ServiceRequest → /order type=testorder)
         → VisitManager.ensureEncounterForOrder() creates new Consultation encounter
         → POST /openmrs/ws/rest/v1/order → TestOrder created (e.g. ORD-318)

[Step 7] Clinician at facility sees TestOrder in OpenMRS O3 Orders widget
         → Creates Encounter + Observation + Condition + MedicationRequest in O3
```

### 3.2 Reverse Flow — OpenMRS → SPICE (Counter-Referral / Status Update)

```
[Step 8] OpenMRS Emitter polls OpenMRS FHIR2 API
         → GET /Encounter?_lastUpdated=gt{ts} → detects new Encounter
         → GET /Condition?_lastUpdated=gt{ts} → detects new Condition
         → GET /MedicationRequest?_lastUpdated=gt{ts} → detects new MedicationRequest
         → Forwards each individually to OpenHIM

[Step 9] OpenHIM routes to SPICE receiver adaptor (future component)
         → SPICE receiver resolves patient by NID
         → Calls POST /patient/referral-tickets/update in fhir-mapper
         → fhir-mapper updates HAPI FHIR ServiceRequest.status = COMPLETED

[Step 10] CHW sees updated status in SPICE mobile app
          → Referral loop closed
```

### 3.3 Over Beckn (removed)

The demo's Beckn path for Spice referrals (`POST /beckn/bpp-webhook/update` and the `select` / `init` / `confirm` / `status` health-referral flow) has been removed. Referrals reach the adaptor only through `/api/v1/openmrs/fhir`, as described above. The adaptor's only network endpoint now is `POST /sygnal/signal/emit`, for CCE care alerts ([sygnal-integration.md](sygnal-integration.md)).

---

## 4. Component Responsibilities

| Component | Role | Port |
|-----------|------|------|
| `spice-service` | SPICE business logic (assessment, referral creation) | 8087 |
| `fhir-mapper` | Converts SPICE DTOs → FHIR resources, stores in HAPI | 8091 |
| `HAPI FHIR JPA` | FHIR R4 server — shared repository between SPICE and emitter | 8090 |
| `openmrs-cce-emitter-adaptor` | Polls HAPI FHIR every 30s, forwards changes to OpenHIM | polling only |
| `OpenHIM` | Mediator — wraps FHIR in CloudEvents, routes to receiver | varies |
| `openmrs-cce-receiver-adaptor` | Receives FHIR from OpenHIM, transforms & writes to OpenMRS | 8888 |
| `OpenMRS O3` | Facility EHR — patient chart, orders, encounters, conditions | 9096 |

---

## 5. FHIR ServiceRequest Structure (Referral Ticket)

When SPICE creates a referral, fhir-mapper generates a FHIR `ServiceRequest` with this structure:

```json
{
  "resourceType": "ServiceRequest",
  "id": "sr-<uuid>",
  "identifier": [
    {
      "system": "http://host.docker.internal:8090/fhir/patient-status",
      "value": "REFERRED"
    },
    {
      "system": "http://host.docker.internal:8090/fhir/patient-current-status",
      "value": "REFERRED"
    },
    {
      "system": "http://host.docker.internal:8090/fhir/category",
      "value": "NCD"
    },
    {
      "system": "http://host.docker.internal:8090/fhir/encounter-type",
      "value": "NCD_MEDICAL_REVIEW"
    }
  ],
  "status": "active",
  "intent": "order",
  "priority": "urgent",
  "subject": {
    "reference": "Patient/fhir-patient-uuid"
  },
  "encounter": {
    "reference": "Encounter/fhir-encounter-uuid"
  },
  "authoredOn": "2026-04-22T08:30:00+00:00",
  "requester": {
    "reference": "Organization/spice-origin-site-fhir-id"
  },
  "performer": [
    { "reference": "Practitioner/referred-clinician-fhir-id" },
    { "reference": "RelatedPerson/member-fhir-id" },
    { "reference": "Organization/referred-facility-fhir-id" }
  ],
  "requisition": {
    "system": "http://host.docker.internal:8090/fhir/ticket-type",
    "value": "MEDICAL_REVIEW"
  },
  "patientInstruction": "High blood pressure, uncontrolled. Patient requires facility-level NCD consultation."
}
```

### ServiceRequest Status Values

| SPICE Status | FHIR ServiceRequest.status | Meaning |
|---|---|---|
| REFERRED (active referral) | `active` | CHW referred patient to facility |
| RECOVERED (not referred) | `completed` | Patient recovered, no active referral |
| Follow-up hold | `on-hold` | Referral on hold |
| Post-consultation | `completed` | Facility clinician completed consultation |

---

## 6. OpenMRS Receiver Processing

When the `openmrs-cce-receiver-adaptor` receives a FHIR `ServiceRequest`:

### 6.1 Routing Decision

```
ServiceRequest → REST_ENDPOINT_MAP["ServiceRequest"] = "/order"
              → REST path (default)
              → type = "testorder"
```

### 6.2 Enrichment Pipeline

```
[1] ReferenceResolver    → Patient/NID → resolve to OpenMRS UUID
[2] ConceptResolver      → code.coding → resolve SPICE code to OpenMRS concept UUID
[3] RequiredFieldEnricher → ensure status="active", intent="order", authoredOn present
[4] VisitManager.ensureEncounterForOrder()
    ├── Extract patient UUID from subject.reference
    ├── GET /openmrs/ws/rest/v1/visit?patient={uuid}&includeInactive=false
    ├── If no active visit → POST /openmrs/ws/rest/v1/visit (new visit)
    └── POST /openmrs/ws/rest/v1/encounter (new Consultation encounter)
[5] FhirToRestTransformer → FHIR ServiceRequest → OpenMRS testorder REST payload
```

### 6.3 REST Payload Sent to OpenMRS

```json
{
  "type": "testorder",
  "action": "NEW",
  "concept": "<resolved-concept-uuid>",
  "patient": "<resolved-patient-uuid>",
  "encounter": "<newly-created-encounter-uuid>",
  "orderer": "<resolved-practitioner-uuid>",
  "careSetting": "OUTPATIENT",
  "urgency": "STAT"
}
```

---

## 7. O3 UI Visibility

| Resource Created | Visible in O3 | Location in O3 |
|---|---|---|
| TestOrder (from ServiceRequest) | Yes | Patient Chart → Orders widget |
| Consultation Encounter (auto-created) | Yes | Patient Chart → Visits tab |
| Active Visit (auto-created) | Yes | Patient Chart → top header visit badge |
| FHIR Task | No direct UI | FHIR2 API only: GET /ws/fhir2/R4/Task/{uuid} |

---

## 8. Key Design Decisions

| Decision | Rationale |
|---|---|
| ServiceRequest → testorder (REST) | TestOrder is the native OpenMRS order model for lab/diagnostic requests. FHIR ServiceRequest has no equivalent first-class REST type without this mapping. |
| Always create new encounter for standalone orders | Reusing an existing encounter would attach the order to an older visit note — semantically wrong. Each referral gets its own Consultation encounter. |
| Emitter polls HAPI FHIR (not OpenMRS) | The forward flow goes SPICE → HAPI FHIR. The emitter reads from HAPI — not from OpenMRS FHIR2. |
| OpenMRS emitter polls OpenMRS FHIR2 (reverse flow) | The reverse flow goes OpenMRS → HAPI FHIR. The OpenMRS emitter reads from OpenMRS FHIR2 and forwards to SPICE receiver. |
| Patient matching via NID | NID is the national-level persistent identity. SPICE internal IDs and OpenMRS UUIDs change per system — only NID persists across both. |
| FHIR Task → FHIR2 only | OpenMRS REST `/taskdefinition` is for scheduler background jobs (not clinical tasks). FHIR `Task` must go to `/ws/fhir2/R4/Task`. |

---

## 9. SPICE Referral Status State Machine

```
                 CHW conducts assessment
                          │
                          ▼
                    [SCREENING]
                          │ Assessment flags high risk
                          ▼
                    [REFERRED] ─────────────────────────────────────┐
                 ServiceRequest.status = active                      │
                 Ticket in SPICE = "PENDING"                         │
                          │                                          │
                          │ Facility clinician accepts               │ Facility clinician rejects
                          ▼                                          ▼
                   [ACCEPTED]                                  [REJECTED]
                          │
                          │ Consultation completed in O3
                          ▼
                   [COMPLETED]
            ServiceRequest.status = completed
                          │
                          │ SPICE receiver receives update
                          ▼
                 Referral loop closed in SPICE
                 CHW notified via follow-up queue
```

---

## 10. Facility (Organization) Mapping

SPICE sites and OpenMRS Locations are mapped via FHIR Organization resources in HAPI FHIR. The `Organization.identifier` holds the site ID from both systems.

| SPICE | FHIR HAPI | OpenMRS |
|---|---|---|
| `siteId` (Long) | `Organization.id` (FHIR UUID) | `Location.uuid` |
| `siteFhirId` (String) | `Organization.identifier[].value` | — |

The `requester.reference` and `performer.reference` in ServiceRequest use FHIR Organization UUIDs, which are pre-populated in HAPI FHIR during facility setup.

---

## 11. Related Documents

- [`referral-use-case-api-walkthrough.md`](./referral-use-case-api-walkthrough.md) — Step-by-step API requests/responses at every stage including prerequisites
- [`sygnal-integration.md`](./sygnal-integration.md) — CCE care alerts over Sygnal (the Beckn referral path is removed)
- [`.github/copilot-instructions-rest-first.md`](../.github/copilot-instructions-rest-first.md) — Full codebase design documentation for the receiver adaptor
