# SPICE ↔ OpenMRS Referral Use Case — Step-by-Step API Walkthrough

> **Document Purpose:** Detailed API request/response examples at every step of the bi-directional referral use case. Covers cross-system mapping prerequisites, forward referral flow (SPICE → OpenMRS), and reverse flow (OpenMRS → SPICE).
>
> **Assumptions:**
> - SPICE 2.0 is fully operational: auth, household, village, facility, CHW users are already configured.
> - OpenMRS O3 is up and running with default configuration.
> - HAPI FHIR JPA server is running at `http://localhost:8090`.
> - CCE Receiver Adaptor is running at `http://localhost:8888`.
> - This document covers **only the cross-system mapping prerequisites** required to link the two systems for referral processing.
>
> See [`spice-openmrs-referral-use-case.md`](./spice-openmrs-referral-use-case.md) for the architecture overview.
>
> **Over Beckn: removed.** The demo Beckn path for Spice referrals (`/beckn/bpp-webhook/update`) no longer exists; referrals arrive only on `/api/v1/openmrs/fhir`. See [sygnal-integration.md](sygnal-integration.md) for what the adaptor receives over the network now (CCE care alerts).

---

## Table of Contents

1. [System Endpoints Reference](#system-endpoints-reference)
2. [Cross-System Prerequisites Overview](#1-cross-system-prerequisites-overview)
3. [OpenMRS — Patient Identifier Types](#2-openmrs--patient-identifier-types)
4. [OpenMRS — Facility (Location)](#3-openmrs--facility-location)
5. [OpenMRS — Provider (Practitioner)](#4-openmrs--provider-practitioner)
6. [OpenMRS — Concept Mapping for SPICE Codes](#5-openmrs--concept-mapping-for-spice-codes)
7. [HAPI FHIR — Organization (Facility)](#6-hapi-fhir--organization-facility)
8. [Patient Registration in Both Systems](#7-patient-registration-in-both-systems)
9. [SPICE Assessment with Referral (Forward Flow)](#8-spice-assessment-with-referral-forward-flow)
10. [FHIR ServiceRequest Created by fhir-mapper](#9-fhir-servicerequest-created-by-fhir-mapper)
11. [Emitter Polling and Forwarding](#10-emitter-polling-and-forwarding)
12. [Receiver Adaptor Processing ServiceRequest](#11-receiver-adaptor-processing-servicerequest)
13. [Clinician Actions in OpenMRS O3 (Reverse Flow)](#12-clinician-actions-in-openmrs-o3-reverse-flow)
14. [Status Update Back to SPICE](#13-status-update-back-to-spice)
15. [End-to-End Verification Checklist](#14-end-to-end-verification-checklist)

---

## System Endpoints Reference

| System | Base URL | Purpose |
|--------|----------|---------|
| SPICE spice-service | `http://localhost:8087` | Community health business logic |
| SPICE fhir-mapper | `http://localhost:8091` | FHIR resource generation |
| HAPI FHIR JPA | `http://localhost:8090/fhir` | Shared FHIR server (SPICE ↔ Emitter) |
| OpenMRS REST v1 | `http://localhost:9096/openmrs/ws/rest/v1` | OpenMRS REST API |
| OpenMRS FHIR2 | `http://localhost:9096/openmrs/ws/fhir2/R4` | OpenMRS FHIR R4 API |
| CCE Receiver Adaptor | `http://localhost:8888` | FHIR → OpenMRS transformer |

**Authentication:**
- SPICE APIs: `Authorization: Bearer <jwt-token>` (already available)
- OpenMRS APIs: `Authorization: Basic YWRtaW46QWRtaW4xMjM=` (admin:Admin123)
- Receiver Adaptor: No auth in dev mode (`cce.security.enabled=false`)

---

## 1. Cross-System Prerequisites Overview

The referral forward flow requires the following entities to exist **in both systems** with a shared identifier before any referral can be processed:

| What | SPICE Side | OpenMRS Side | Shared Key |
|------|-----------|-------------|-----------|
| **Patient** | `Patient` + `RelatedPerson` in HAPI FHIR, `nationalId` field | Patient with NID identifier | National ID (NID) or SPICE Virtual ID |
| **Facility** | `Organization` in HAPI FHIR, `siteId` in SPICE DB | `Location` in OpenMRS | No auto-sync — manually maintained |
| **Clinician** | `Practitioner` in HAPI FHIR (SPICE user FHIR ID) | `Provider` in OpenMRS | Practitioner FHIR ID or identifier string |
| **Concept** | SPICE disease code (e.g. `NCD`) | OpenMRS concept with SPICE source mapping | `code` + `system=SPICE` |
| **Identifier type** | `nationalId` field (NID string) | `PatientIdentifierType` named "National ID" | Name match |

### Why These Are Needed

- **Patient**: The `ReferenceResolver` resolves `Patient/ET/ADM/2023/001234` → OpenMRS UUID using `GET /patient?identifier={nid}`. Without a matching NID identifier type + value in OpenMRS, the patient cannot be resolved.
- **Facility (Organization)**: The `ServiceRequest.performer` and `ServiceRequest.requester` reference Organization UUIDs. These must exist in HAPI FHIR for the emitter to forward the full resource without errors.
- **Clinician (Provider)**: The `ServiceRequest.requester` is mapped to the `orderer` in the REST testorder. The `ReferenceResolver` resolves `Practitioner/<id>` → OpenMRS Provider UUID. Without a matching provider, the order will fail with 400.
- **Concept**: The `ConceptResolver` converts `code.coding[system=SPICE, code=NCD]` → OpenMRS concept UUID. Without this mapping, the ServiceRequest cannot be transformed to a testorder.

---

## 2. OpenMRS — Patient Identifier Types

Two identifier types must exist in OpenMRS to match patients from SPICE.

### 2.1 Create "National ID" Identifier Type

Used for patients with a government-issued National Identification Number (Fayda ID in Ethiopia).

**Request:**
```http
POST http://localhost:9096/openmrs/ws/rest/v1/patientidentifiertype
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "name": "National ID",
  "description": "Ethiopian National Identification Number (Fayda ID). Used as the cross-system patient identifier between SPICE and OpenMRS.",
  "format": "",
  "formatDescription": "",
  "required": false,
  "checkDigit": false,
  "validator": "",
  "locationBehavior": null,
  "uniquenessType": "UNIQUE"
}
```

**Response:** `201 Created`
```json
{
  "uuid": "nid-type-uuid-0001",
  "display": "National ID",
  "name": "National ID",
  "description": "Ethiopian National Identification Number (Fayda ID). Used as the cross-system patient identifier between SPICE and OpenMRS.",
  "required": false,
  "uniquenessType": "UNIQUE",
  "retired": false
}
```

> **Save:** `uuid` = `nid-type-uuid-0001`
>
> The `ReferenceResolver` searches with `GET /patient?identifier={nid_value}` — this works because OpenMRS searches across all identifier types by value.

---

### 2.2 Create "SPICE Virtual ID" Identifier Type

For patients who were registered in SPICE without a national ID, SPICE generates its own virtual ID. Create a separate identifier type for this.

**Request:**
```http
POST http://localhost:9096/openmrs/ws/rest/v1/patientidentifiertype
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "name": "SPICE Virtual ID",
  "description": "SPICE-generated virtual identifier for patients without a national ID.",
  "format": "",
  "required": false,
  "checkDigit": false,
  "uniquenessType": "UNIQUE"
}
```

**Response:** `201 Created`
```json
{
  "uuid": "spice-virtual-id-type-uuid-0002",
  "display": "SPICE Virtual ID",
  "name": "SPICE Virtual ID",
  "required": false,
  "retired": false
}
```

> **Save:** `uuid` = `spice-virtual-id-type-uuid-0002`

---

### 2.3 Verify Both Identifier Types Exist

```http
GET http://localhost:9096/openmrs/ws/rest/v1/patientidentifiertype?v=default
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

**Response:** `200 OK`
```json
{
  "results": [
    {
      "uuid": "05a29f94-c0ed-11e2-94be-8c13b969e334",
      "display": "OpenMRS ID",
      "required": true
    },
    {
      "uuid": "nid-type-uuid-0001",
      "display": "National ID",
      "required": false
    },
    {
      "uuid": "spice-virtual-id-type-uuid-0002",
      "display": "SPICE Virtual ID",
      "required": false
    }
  ]
}
```

---

## 3. OpenMRS — Facility (Location)

The facility that receives the referral must exist as a `Location` in OpenMRS. This is used as the visit location when the receiver adaptor creates an encounter for standalone orders.

### 3.1 Create the Facility Location

**Request:**
```http
POST http://localhost:9096/openmrs/ws/rest/v1/location
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "name": "Addis Ketema Health Center",
  "description": "Primary health care facility. Corresponds to SPICE siteId=42, HAPI Organization=org-fhir-uuid-akhc-001.",
  "address1": "Addis Ketema, Sub-city 05",
  "cityVillage": "Addis Ababa",
  "stateProvince": "Addis Ababa",
  "country": "Ethiopia",
  "postalCode": "1000",
  "longitude": "38.7578",
  "latitude": "9.0054",
  "tags": [
    { "uuid": "b8bbf83e-645f-451f-8efe-a0db56eefa2b" }
  ]
}
```

> The `tags` array should reference the "Login Location" tag UUID from your OpenMRS instance:
> ```http
> GET /openmrs/ws/rest/v1/locationtag?v=default
> ```

**Response:** `201 Created`
```json
{
  "uuid": "location-uuid-akhc-001",
  "display": "Addis Ketema Health Center",
  "name": "Addis Ketema Health Center",
  "cityVillage": "Addis Ababa",
  "country": "Ethiopia",
  "retired": false
}
```

> **Save:** `uuid` = `location-uuid-akhc-001`
>
> Used by `VisitManager` when creating visits and encounters for standalone orders. Configure via `openmrs.identifier.locationUuid` or auto-discovered at startup.

---

### 3.2 Verify Visit Type Exists (Used by VisitManager)

```http
GET http://localhost:9096/openmrs/ws/rest/v1/visittype?v=default
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

**Response:** `200 OK`
```json
{
  "results": [
    {
      "uuid": "7b0f5697-27e3-40c4-8bae-f4049abfb4ed",
      "display": "Facility Visit",
      "name": "Facility Visit"
    }
  ]
}
```

> The receiver adaptor auto-discovers the visit type UUID at startup (prefers "Facility Visit", falls back to first available).

---

### 3.3 Verify Consultation Encounter Type Exists (Used by VisitManager)

When a standalone `ServiceRequest` arrives without an `encounter` reference, `VisitManager.ensureEncounterForOrder()` creates a new "Consultation" encounter. This encounter type must exist.

```http
GET http://localhost:9096/openmrs/ws/rest/v1/encountertype?v=default
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

**Response:** `200 OK`
```json
{
  "results": [
    {
      "uuid": "consultation-enc-type-uuid",
      "display": "Consultation",
      "name": "Consultation"
    }
  ]
}
```

> The receiver adaptor caches all encounter types at startup in `DiscoveredConfig.encounterTypeCache`. No manual configuration needed — but "Consultation" must appear in the list.

---

## 4. OpenMRS — Provider (Practitioner)

The SPICE CHW who creates the referral appears as `ServiceRequest.requester` (a `Practitioner` reference). The `ReferenceResolver` maps this to an OpenMRS `Provider` UUID. A matching provider must exist in OpenMRS.

### 4.1 Create the Person Record

```http
POST http://localhost:9096/openmrs/ws/rest/v1/person
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "names": [
    {
      "givenName": "Hana",
      "familyName": "Bekele",
      "preferred": true
    }
  ],
  "gender": "F",
  "birthdate": "1990-06-10"
}
```

**Response:** `201 Created`
```json
{
  "uuid": "person-uuid-hana",
  "display": "Hana Bekele",
  "gender": "F"
}
```

---

### 4.2 Create the Provider Linking to the Person

The `identifier` must match the SPICE practitioner FHIR ID so the `ReferenceResolver` can look it up.

```http
POST http://localhost:9096/openmrs/ws/rest/v1/provider
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "person": "person-uuid-hana",
  "identifier": "practitioner-fhir-uuid-chw-101",
  "retired": false
}
```

**Response:** `201 Created`
```json
{
  "uuid": "provider-uuid-hana",
  "display": "practitioner-fhir-uuid-chw-101 - Hana Bekele",
  "person": { "uuid": "person-uuid-hana", "display": "Hana Bekele" },
  "identifier": "practitioner-fhir-uuid-chw-101",
  "retired": false
}
```

> **Save:** `uuid` = `provider-uuid-hana`
>
> **How it gets resolved:** When the receiver adaptor gets `"requester": {"reference": "Practitioner/practitioner-fhir-uuid-chw-101"}`, the `ReferenceResolver` executes:
> ```
> GET /provider?q=practitioner-fhir-uuid-chw-101&v=default
> → results[0].uuid = "provider-uuid-hana"
> ```
> This UUID becomes `"orderer"` in the REST testorder payload.

---

### 4.3 (Optional) Create the Receiving Clinician Provider

If the ServiceRequest includes a `performer` referencing a specific facility clinician, that clinician should also exist as a Provider. Repeat steps 4.1–4.2 for each clinician.

---

## 5. OpenMRS — Concept Mapping for SPICE Codes

The `ConceptResolver` converts `ServiceRequest.code.coding[system=SPICE, code=NCD]` → OpenMRS concept UUID. Each SPICE disease category code must have a corresponding concept in OpenMRS with a SPICE source mapping.

### 5.1 Create a SPICE Concept Source (One-Time Setup)

**Check if "SPICE" source already exists:**
```http
GET http://localhost:9096/openmrs/ws/rest/v1/conceptsource?v=default
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

If "SPICE" is not in the results, create it:

```http
POST http://localhost:9096/openmrs/ws/rest/v1/conceptsource
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "name": "SPICE",
  "description": "Medtronic LABS SPICE platform concept codes",
  "hl7Code": "SPICE"
}
```

**Response:** `201 Created`
```json
{
  "uuid": "spice-source-uuid",
  "display": "SPICE",
  "name": "SPICE",
  "hl7Code": "SPICE",
  "retired": false
}
```

> **Save:** `uuid` = `spice-source-uuid`

---

### 5.2 Create the NCD Referral Concept

The `ServiceRequest.code` from SPICE uses `system = "https://openconceptlab.org/orgs/Medtronic-LABS/sources/SPICE"` and `code = "NCD"`.

```http
POST http://localhost:9096/openmrs/ws/rest/v1/concept
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "names": [
    {
      "name": "NCD Referral",
      "locale": "en",
      "conceptNameType": "FULLY_SPECIFIED",
      "localePreferred": true
    }
  ],
  "datatype": {
    "uuid": "8d4a4c94-c2cc-11de-8d13-0010c6dffd0f"
  },
  "conceptClass": {
    "uuid": "8d490dfc-c2cc-11de-8d13-0010c6dffd0f"
  },
  "mappings": [
    {
      "conceptReferenceTerm": {
        "code": "NCD",
        "conceptSource": {
          "uuid": "spice-source-uuid"
        }
      },
      "conceptMapType": {
        "uuid": "35543629-7d8c-11e1-909d-c80aa9edcf4e"
      }
    }
  ]
}
```

> - **Datatype UUID** `8d4a4c94-c2cc-11de-8d13-0010c6dffd0f` = "N/A" (non-observable procedure order)
> - **ConceptClass UUID** `8d490dfc-c2cc-11de-8d13-0010c6dffd0f` = "Procedure"
> - **ConceptMapType UUID** `35543629-7d8c-11e1-909d-c80aa9edcf4e` = "SAME-AS"
>
> To find the correct UUIDs for your OpenMRS instance:
> ```http
> GET /openmrs/ws/rest/v1/conceptdatatype?v=default
> GET /openmrs/ws/rest/v1/conceptclass?v=default
> GET /openmrs/ws/rest/v1/conceptmaptype?v=default
> ```

**Response:** `201 Created`
```json
{
  "uuid": "ncd-referral-concept-uuid",
  "display": "NCD Referral",
  "datatype": { "display": "N/A" },
  "conceptClass": { "display": "Procedure" },
  "mappings": [
    {
      "conceptReferenceTerm": { "code": "NCD", "conceptSource": { "name": "SPICE" } },
      "conceptMapType": { "display": "SAME-AS" }
    }
  ]
}
```

> **Save:** `uuid` = `ncd-referral-concept-uuid`

---

### 5.3 Verify Concept Resolution (Exact ConceptResolver Lookup)

```http
GET http://localhost:9096/openmrs/ws/rest/v1/concept?source=SPICE&code=NCD&v=default
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

**Response:** `200 OK`
```json
{
  "results": [
    {
      "uuid": "ncd-referral-concept-uuid",
      "display": "NCD Referral",
      "mappings": [
        {
          "conceptReferenceTerm": { "code": "NCD", "conceptSource": { "name": "SPICE" } }
        }
      ]
    }
  ]
}
```

> If `results` is empty, `ConceptResolver` throws `ResourceTransformException` and the ServiceRequest processing fails with 422.

---

### 5.4 Additional SPICE Concept Codes (Repeat per Category)

Create one concept per SPICE disease/referral category code using the same pattern as § 5.2:

| SPICE Code | Suggested OpenMRS Concept Name |
|------------|-------------------------------|
| `NCD` | NCD Referral |
| `MENTAL_HEALTH` | Mental Health Referral |
| `HIV` | HIV Referral |
| `MATERNAL_HEALTH` | Maternal Health Referral |
| `ICCM` | ICCM Referral |
| `TB` | TB Referral |

---

## 6. HAPI FHIR — Organization (Facility)

When fhir-mapper creates a `ServiceRequest`, it uses `Organization/<fhirId>` for `requester` and `performer`. The HAPI FHIR server must have these Organization resources. Typically auto-created when SPICE admin creates a site — verify or create manually if needed.

### 6.1 Verify Organization Exists in HAPI FHIR

```http
GET http://localhost:8090/fhir/Organization?identifier=42
```

If not found:

### 6.2 Create Organization in HAPI FHIR

```http
PUT http://localhost:8090/fhir/Organization/org-fhir-uuid-akhc-001
Content-Type: application/fhir+json

{
  "resourceType": "Organization",
  "id": "org-fhir-uuid-akhc-001",
  "identifier": [
    {
      "system": "http://host.docker.internal:8090/fhir/organization-id",
      "value": "42"
    }
  ],
  "name": "Addis Ketema Health Center",
  "active": true,
  "type": [
    {
      "coding": [
        {
          "system": "http://terminology.hl7.org/CodeSystem/organization-type",
          "code": "prov",
          "display": "Healthcare Provider"
        }
      ]
    }
  ],
  "address": [
    { "city": "Addis Ababa", "country": "Ethiopia" }
  ]
}
```

**Response:** `201 Created`
```json
{
  "resourceType": "Organization",
  "id": "org-fhir-uuid-akhc-001",
  "meta": { "versionId": "1", "lastUpdated": "2026-04-22T08:00:00.000+00:00" },
  "name": "Addis Ketema Health Center",
  "active": true
}
```

---

## 7. Patient Registration in Both Systems

The same patient must exist in both SPICE and OpenMRS sharing the same NID — this is the primary key for cross-system patient matching.

### 7.1 Patient Already in SPICE (Assumed)

SPICE has the patient registered with:
- `patientId` = `patient-spice-id-8890`
- `nationalId` = `ET/ADM/2023/001234`
- `memberReference` = `RelatedPerson/rp-fhir-uuid-001`

The fhir-mapper also created a `Patient` resource in HAPI FHIR. Verify:

```http
GET http://localhost:8090/fhir/Patient?identifier=ET/ADM/2023/001234
```

**Response:** `200 OK` — Bundle with Patient `id = fhir-patient-uuid-001`.

---

### 7.2 Register the Same Patient in OpenMRS

The patient must exist in OpenMRS with the **same NID value** using the "National ID" identifier type created in § 2.

```http
POST http://localhost:9096/openmrs/ws/rest/v1/patient
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "identifiers": [
    {
      "identifier": "100GXY",
      "identifierType": "05a29f94-c0ed-11e2-94be-8c13b969e334",
      "location": "location-uuid-akhc-001",
      "preferred": true
    },
    {
      "identifier": "ET/ADM/2023/001234",
      "identifierType": "nid-type-uuid-0001",
      "location": "location-uuid-akhc-001",
      "preferred": false
    }
  ],
  "person": {
    "names": [
      { "givenName": "Abebe", "familyName": "Girma", "preferred": true }
    ],
    "gender": "M",
    "birthdate": "1978-03-15",
    "addresses": [
      {
        "address1": "Arada Kebele 05",
        "cityVillage": "Addis Ababa",
        "stateProvince": "Addis Ababa",
        "country": "Ethiopia",
        "preferred": true
      }
    ]
  }
}
```

**Response:** `201 Created`
```json
{
  "uuid": "patient-openmrs-uuid-abebe",
  "display": "100GXY - Abebe Girma",
  "identifiers": [
    {
      "identifier": "100GXY",
      "identifierType": { "display": "OpenMRS ID" },
      "preferred": true
    },
    {
      "identifier": "ET/ADM/2023/001234",
      "identifierType": { "display": "National ID" },
      "preferred": false
    }
  ],
  "person": {
    "uuid": "person-uuid-abebe",
    "display": "Abebe Girma",
    "gender": "M",
    "birthdate": "1978-03-15T00:00:00.000+0000"
  }
}
```

> **Save:** `uuid` = `patient-openmrs-uuid-abebe`

---

### 7.3 Verify Patient Resolution (Exact ReferenceResolver Lookup)

This is the exact query the `ReferenceResolver` executes when it encounters `"reference": "Patient/ET/ADM/2023/001234"`:

```http
GET http://localhost:9096/openmrs/ws/rest/v1/patient?identifier=ET/ADM/2023/001234&v=default
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

**Response:** `200 OK`
```json
{
  "results": [
    {
      "uuid": "patient-openmrs-uuid-abebe",
      "display": "100GXY - Abebe Girma",
      "identifiers": [
        {
          "identifier": "ET/ADM/2023/001234",
          "identifierType": { "display": "National ID" }
        }
      ]
    }
  ]
}
```

> `results[0].uuid` is extracted and `Patient/ET/ADM/2023/001234` is replaced with `Patient/patient-openmrs-uuid-abebe` before transformation.

---

## 8. SPICE Assessment with Referral (Forward Flow)

### 8.1 CHW Submits NCD Assessment with Referral Flag

```http
POST http://localhost:8087/assessment/create
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "assessmentType": "NCD",
  "patientId": "patient-spice-id-8890",
  "patientReference": "RelatedPerson/rp-fhir-uuid-001",
  "memberReference": "RelatedPerson/rp-fhir-uuid-001",
  "assessmentOrganizationId": "org-fhir-uuid-akhc-001",
  "assessmentTakenOn": "2026-04-22T09:30:00.000Z",
  "villageId": "55",
  "referralTicketType": "NCD",
  "isReferAssessment": true,
  "riskLevel": "HIGH",
  "riskMessage": "Severely elevated blood pressure (Stage 2 Hypertension)",
  "bioData": {
    "firstName": "Abebe",
    "lastName": "Girma",
    "nationalId": "ET/ADM/2023/001234",
    "identityType": "NATIONAL_ID",
    "identityValue": "ET/ADM/2023/001234",
    "gender": "Male",
    "phoneNumber": "+251912345678",
    "phoneNumberCategory": "PERSONAL",
    "siteName": "Addis Ketema Health Center",
    "siteFhirId": "org-fhir-uuid-akhc-001"
  },
  "bioMetrics": {
    "gender": "Male",
    "age": 48,
    "dateOfBirth": "1978-03-15",
    "height": 172.0,
    "weight": 86.0,
    "bmi": 29.1,
    "bmiCategory": "OVERWEIGHT",
    "isRegularSmoker": false
  },
  "bpLog": {
    "avgSystolic": 168,
    "avgDiastolic": 104,
    "avgBloodPressure": "168/104",
    "avgPulse": 88,
    "cvdRiskLevel": "HIGH",
    "cvdRiskScore": 18.5,
    "bpTakenOn": "2026-04-22T09:30:00.000Z",
    "bpLogDetails": [
      { "systolic": 170, "diastolic": 106, "pulse": 90 },
      { "systolic": 167, "diastolic": 103, "pulse": 87 },
      { "systolic": 166, "diastolic": 104, "pulse": 88 }
    ]
  },
  "ncdSymptoms": [
    { "name": "Headache", "code": "headache" },
    { "name": "Dizziness", "code": "dizziness" }
  ],
  "encounter": {
    "patientId": "patient-spice-id-8890",
    "patientReference": "RelatedPerson/rp-fhir-uuid-001",
    "memberId": "rp-fhir-uuid-001",
    "patientStatus": "REFERRED",
    "referred": true,
    "startTime": "2026-04-22T09:00:00.000Z",
    "endTime": "2026-04-22T09:30:00.000Z",
    "provenance": {
      "userId": "practitioner-fhir-uuid-chw-101",
      "organizationId": "org-fhir-uuid-akhc-001",
      "modifiedDate": "2026-04-22T09:30:00.000Z"
    }
  },
  "provisionalDiagnosis": ["HTN"],
  "referredReasons": "Uncontrolled Stage 2 Hypertension, headache and dizziness. Requires facility-level NCD consultation."
}
```

**Response:** `200 OK`
```json
{
  "assessmentType": "NCD",
  "patientId": "patient-spice-id-8890",
  "patientStatus": "REFERRED",
  "riskLevel": "HIGH",
  "encounter": {
    "id": "encounter-fhir-uuid-assessment-001",
    "patientStatus": "REFERRED",
    "referred": true
  },
  "referralTicketType": "NCD"
}
```

---

### 8.2 Direct Referral Ticket Creation (Alternative)

```http
POST http://localhost:8087/patient/referral-tickets/create
Authorization: Bearer <jwt-token>
Content-Type: application/json

{
  "encounterId": "encounter-fhir-uuid-assessment-001",
  "type": "NCD_MEDICAL_REVIEW",
  "referredReason": "Uncontrolled Stage 2 Hypertension — requires NCD consultation",
  "memberId": "rp-fhir-uuid-001",
  "patientId": "patient-spice-id-8890",
  "patientReference": "Patient/fhir-patient-uuid-001",
  "referredSiteId": "org-fhir-uuid-akhc-001",
  "referredClinicianId": "practitioner-fhir-uuid-chw-101",
  "referred": true,
  "patientStatus": "REFERRED",
  "currentPatientStatus": "REFERRED",
  "category": "NCD",
  "provenance": {
    "userId": "practitioner-fhir-uuid-chw-101",
    "organizationId": "org-fhir-uuid-akhc-001",
    "modifiedDate": "2026-04-22T09:30:00.000Z"
  }
}
```

**Response:** `200 OK`
```json
{
  "status": true,
  "message": "Referral ticket saved",
  "entity": {
    "encounterId": "encounter-fhir-uuid-assessment-001",
    "patientStatus": "REFERRED",
    "referred": true
  }
}
```

---

## 9. FHIR ServiceRequest Created by fhir-mapper

### 9.1 ServiceRequest Stored in HAPI FHIR

The fhir-mapper converts the referral details → FHIR `ServiceRequest` and stores it in HAPI FHIR via an internal `PUT` call.

```json
{
  "resourceType": "ServiceRequest",
  "id": "sr-fhir-uuid-ref-001",
  "identifier": [
    { "system": "http://host.docker.internal:8090/fhir/patient-status", "value": "REFERRED" },
    { "system": "http://host.docker.internal:8090/fhir/patient-current-status", "value": "REFERRED" },
    { "system": "http://host.docker.internal:8090/fhir/category", "value": "NCD" },
    { "system": "http://host.docker.internal:8090/fhir/encounter-type", "value": "NCD_MEDICAL_REVIEW" }
  ],
  "status": "active",
  "intent": "order",
  "priority": "urgent",
  "subject": { "reference": "Patient/fhir-patient-uuid-001" },
  "encounter": { "reference": "Encounter/encounter-fhir-uuid-assessment-001" },
  "authoredOn": "2026-04-22T09:30:00+00:00",
  "requester": { "reference": "Organization/org-fhir-uuid-akhc-001" },
  "performer": [
    { "reference": "Practitioner/practitioner-fhir-uuid-chw-101" },
    { "reference": "RelatedPerson/rp-fhir-uuid-001" },
    { "reference": "Organization/org-fhir-uuid-akhc-001" }
  ],
  "requisition": {
    "system": "http://host.docker.internal:8090/fhir/ticket-type",
    "value": "MEDICAL_REVIEW"
  },
  "patientInstruction": "Uncontrolled Stage 2 Hypertension — requires NCD consultation"
}
```

### 9.2 Verify ServiceRequest in HAPI FHIR

```http
GET http://localhost:8090/fhir/ServiceRequest?subject=Patient/fhir-patient-uuid-001&status=active
```

**Response:** `200 OK` — Bundle containing the active ServiceRequest above.

---

## 10. Emitter Polling and Forwarding

### 10.1 Emitter Detects the New ServiceRequest

The **OpenMRS Emitter Adaptor** polls HAPI FHIR every 30 seconds:

```http
GET http://localhost:8090/fhir/ServiceRequest?_lastUpdated=gt2026-04-22T09:29:45.000%2B00:00&_count=50&_sort=-_lastUpdated
```

**HAPI FHIR Response:** `200 OK` — Bundle with the new `ServiceRequest` (see § 9.1).

### 10.2 Emitter Forwards to OpenHIM

```http
POST http://openhim:5001/ServiceRequest
Authorization: Basic <openhim-credentials>
Content-Type: application/fhir+json

{ ... full ServiceRequest JSON ... }
```

OpenHIM routes the payload to the receiver adaptor.

---

## 11. Receiver Adaptor Processing ServiceRequest

### 11.1 Payload Arriving at Receiver Adaptor

> **Note:** The `subject.reference` must carry a value resolvable by OpenMRS identifier search. The NID (`Patient/ET/ADM/2023/001234`) must be used; a HAPI FHIR UUID alone cannot be resolved in OpenMRS.

```http
POST http://localhost:8888/api/v1/fhir
Content-Type: application/json

{
  "resourceType": "ServiceRequest",
  "id": "sr-fhir-uuid-ref-001",
  "identifier": [
    { "system": "http://host.docker.internal:8090/fhir/patient-status", "value": "REFERRED" },
    { "system": "http://host.docker.internal:8090/fhir/category", "value": "NCD" }
  ],
  "status": "active",
  "intent": "order",
  "priority": "urgent",
  "subject": { "reference": "Patient/ET/ADM/2023/001234" },
  "authoredOn": "2026-04-22T09:30:00+00:00",
  "requester": { "reference": "Practitioner/practitioner-fhir-uuid-chw-101" },
  "patientInstruction": "Uncontrolled Stage 2 Hypertension — requires NCD consultation",
  "code": {
    "coding": [
      {
        "system": "https://openconceptlab.org/orgs/Medtronic-LABS/sources/SPICE",
        "code": "NCD",
        "display": "NCD Referral"
      }
    ],
    "text": "NCD Referral"
  }
}
```

### 11.2 Internal Enrichment Steps

**Step 1 — ReferenceResolver resolves Patient:**
```
GET /patient?identifier=ET/ADM/2023/001234&v=default
→ results[0].uuid = "patient-openmrs-uuid-abebe"
→ subject.reference = "Patient/patient-openmrs-uuid-abebe"
```

**Step 2 — ReferenceResolver resolves Practitioner:**
```
GET /provider?q=practitioner-fhir-uuid-chw-101&v=default
→ results[0].uuid = "provider-uuid-hana"
→ requester.reference = "Practitioner/provider-uuid-hana"
```

**Step 3 — ConceptResolver resolves NCD code:**
```
GET /concept?source=SPICE&code=NCD
→ results[0].uuid = "ncd-referral-concept-uuid"
```

**Step 4 — VisitManager.ensureEncounterForOrder():**
```
GET /visit?patient=patient-openmrs-uuid-abebe&includeInactive=false
→ No active visit found

POST /visit
  { "patient": "patient-openmrs-uuid-abebe",
    "visitType": "7b0f5697-27e3-40c4-8bae-f4049abfb4ed",
    "location": "location-uuid-akhc-001",
    "startDatetime": "2026-04-22T09:30:53.996+0000" }
→ { "uuid": "new-visit-uuid-001" }

POST /encounter
  { "encounterType": "consultation-enc-type-uuid",
    "patient": "patient-openmrs-uuid-abebe",
    "visit": "new-visit-uuid-001",
    "encounterDatetime": "2026-04-22T09:30:53.996+0000" }
→ { "uuid": "new-encounter-uuid-001" }
```

**Step 5 — FhirToRestTransformer converts to testorder:**
```json
{
  "type": "testorder",
  "action": "NEW",
  "concept": "ncd-referral-concept-uuid",
  "patient": "patient-openmrs-uuid-abebe",
  "encounter": "new-encounter-uuid-001",
  "orderer": "provider-uuid-hana",
  "careSetting": "OUTPATIENT",
  "urgency": "STAT"
}
```

### 11.3 TestOrder Sent to OpenMRS

```http
POST http://localhost:9096/openmrs/ws/rest/v1/order
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "type": "testorder",
  "action": "NEW",
  "concept": "ncd-referral-concept-uuid",
  "patient": "patient-openmrs-uuid-abebe",
  "encounter": "new-encounter-uuid-001",
  "orderer": "provider-uuid-hana",
  "careSetting": "OUTPATIENT",
  "urgency": "STAT"
}
```

**OpenMRS Response:** `201 Created`
```json
{
  "uuid": "order-uuid-ORD-318",
  "orderNumber": "ORD-318",
  "type": "testorder",
  "concept": { "display": "NCD Referral" },
  "encounter": { "display": "Consultation 22/04/2026" },
  "orderer": { "display": "Hana Bekele" },
  "urgency": "STAT"
}
```

### 11.4 Receiver Adaptor Response

```json
{
  "totalEntries": 1,
  "succeeded": 1,
  "failed": 0,
  "results": [
    {
      "resourceType": "ServiceRequest",
      "resourceId": "order-uuid-ORD-318",
      "route": "rest",
      "status": "created",
      "httpStatus": 201,
      "errorMessage": null
    }
  ]
}
```

---

## 12. Clinician Actions in OpenMRS O3 (Reverse Flow)

### 12.1 Verify TestOrder in O3

```http
GET http://localhost:9096/openmrs/ws/rest/v1/order?patient=patient-openmrs-uuid-abebe&v=default
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

**Response:** `200 OK` — Returns ORD-318 with `"type": "testorder"`.

### 12.2 Clinician Creates Consultation Encounter

```http
POST http://localhost:9096/openmrs/ws/rest/v1/encounter
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "encounterType": "consultation-enc-type-uuid",
  "patient": "patient-openmrs-uuid-abebe",
  "visit": "new-visit-uuid-001",
  "location": "location-uuid-akhc-001",
  "encounterDatetime": "2026-04-22T14:00:00.000+0000",
  "encounterProviders": [
    {
      "provider": "provider-uuid-hana",
      "encounterRole": "240b26f9-dd88-4172-823d-4a8bfeb7841f"
    }
  ]
}
```

**Response:** `201 Created`
```json
{
  "uuid": "encounter-uuid-consultation-001",
  "display": "Consultation 22/04/2026",
  "encounterDatetime": "2026-04-22T14:00:00.000+0000"
}
```

### 12.3 Record BP Observation

```http
POST http://localhost:9096/openmrs/ws/rest/v1/obs
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "person": "patient-openmrs-uuid-abebe",
  "concept": "5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "obsDatetime": "2026-04-22T14:10:00.000+0000",
  "encounter": "encounter-uuid-consultation-001",
  "value": 162
}
```

**Response:** `201 Created` — `{ "uuid": "obs-uuid-systolic-001", "value": 162 }`

### 12.4 Confirm Diagnosis

```http
POST http://localhost:9096/openmrs/ws/rest/v1/condition
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "patient": "patient-openmrs-uuid-abebe",
  "condition": { "coded": "117399AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" },
  "clinicalStatus": "ACTIVE",
  "verificationStatus": "CONFIRMED",
  "onsetDate": "2026-04-22T14:00:00.000+0000"
}
```

**Response:** `201 Created` — `{ "uuid": "condition-uuid-htn-001", "display": "Hypertension" }`

### 12.5 Prescribe Medication

```http
POST http://localhost:9096/openmrs/ws/rest/v1/order
Authorization: Basic YWRtaW46QWRtaW4xMjM=
Content-Type: application/json

{
  "type": "drugorder",
  "action": "NEW",
  "patient": "patient-openmrs-uuid-abebe",
  "encounter": "encounter-uuid-consultation-001",
  "orderer": "provider-uuid-hana",
  "careSetting": "OUTPATIENT",
  "concept": "71617AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
  "drug": "amlodipine-drug-uuid",
  "dose": 5.0,
  "doseUnits": "mg-units-uuid",
  "route": "oral-route-uuid",
  "frequency": "once-daily-frequency-uuid",
  "quantity": 30.0,
  "duration": 30,
  "numRefills": 2
}
```

**Response:** `201 Created` — `{ "orderNumber": "ORD-319", "type": "drugorder", "concept": { "display": "Amlodipine" } }`

---

## 13. Status Update Back to SPICE

### 13.1 OpenMRS Emitter Detects Reverse Flow Resources

```http
GET http://localhost:9096/openmrs/ws/fhir2/R4/Encounter?_lastUpdated=gt2026-04-22T13:59:45.000%2B00:00&_count=50
Authorization: Basic YWRtaW46QWRtaW4xMjM=
```

**Response:** `200 OK` — Bundle with `encounter-uuid-consultation-001`. The emitter also polls `/Condition` and `/MedicationRequest` similarly, then forwards all three to OpenHIM.

### 13.2 fhir-mapper Updates ServiceRequest Status

> **Note:** The SPICE receiver adaptor (counterpart component) is under design. The expected flow calls:

```http
POST http://localhost:8091/patient/referral-tickets/update
Content-Type: application/json

{
  "memberId": "rp-fhir-uuid-001",
  "patientReference": "Patient/fhir-patient-uuid-001",
  "patientStatus": "VISITED",
  "currentPatientStatus": "COMPLETED",
  "referred": false,
  "provenance": {
    "userId": "provider-uuid-hana",
    "organizationId": "org-fhir-uuid-akhc-001",
    "modifiedDate": "2026-04-22T14:45:00.000Z"
  }
}
```

fhir-mapper then updates `ServiceRequest.status = completed` in HAPI FHIR.

### 13.3 Verify Updated Referral in SPICE

```http
POST http://localhost:8087/patient/referral-tickets
Authorization: Bearer <jwt-token>
Content-Type: application/json

{ "patientId": "patient-spice-id-8890", "memberId": "rp-fhir-uuid-001" }
```

**Response:** `200 OK`
```json
{
  "entity": {
    "patientStatus": "VISITED",
    "referredDates": [
      { "date": "2026-04-22T09:30:00.000Z", "status": "REFERRED" },
      { "date": "2026-04-22T14:45:00.000Z", "status": "VISITED" }
    ]
  }
}
```

---

## 14. End-to-End Verification Checklist

### Cross-System Mapping Prerequisites

| Check | API Call | Expected |
|-------|---------|----------|
| NID identifier type exists | `GET /patientidentifiertype?v=default` | "National ID" in results |
| SPICE Virtual ID type exists | `GET /patientidentifiertype?v=default` | "SPICE Virtual ID" in results |
| Facility location exists in OpenMRS | `GET /location?q=Addis+Ketema+Health+Center` | Returns location UUID |
| HAPI FHIR Organization for facility | `GET /fhir/Organization/org-fhir-uuid-akhc-001` | 200 OK |
| Provider exists with SPICE FHIR ID | `GET /provider?q=practitioner-fhir-uuid-chw-101` | Returns provider UUID |
| SPICE concept source exists | `GET /conceptsource?v=default` | "SPICE" in results |
| NCD concept has SPICE mapping | `GET /concept?source=SPICE&code=NCD` | Returns ncd-referral-concept-uuid |
| Patient in OpenMRS with NID | `GET /patient?identifier=ET/ADM/2023/001234` | Returns 1 result |
| Patient in HAPI FHIR with NID | `GET /fhir/Patient?identifier=ET/ADM/2023/001234` | Returns fhir-patient-uuid-001 |
| Adaptor running | `GET http://localhost:8888/actuator/health` | `{"status":"UP"}` |

### Forward Flow Verification

| Step | How to Verify |
|------|--------------|
| Referral created in SPICE | SPICE patient detail shows `patientStatus = REFERRED` |
| ServiceRequest in HAPI FHIR | `GET /fhir/ServiceRequest?subject=Patient/fhir-patient-uuid-001` → `status=active` |
| Emitter detects and forwards | Emitter log: `"Forwarded 1 ServiceRequest resource"` |
| TestOrder in OpenMRS | `GET /order?patient=patient-openmrs-uuid-abebe` → ORD-xxx visible |
| Visit created | `GET /visit?patient=patient-openmrs-uuid-abebe&includeInactive=false` → Facility Visit |
| Encounter created | `GET /encounter?patient=patient-openmrs-uuid-abebe` → Consultation 22/04/2026 |
| TestOrder visible in O3 | O3 patient chart → Orders widget → "NCD Referral" |

### Quick Test — Send ServiceRequest Directly

```bash
curl -s -X POST http://localhost:8888/api/v1/fhir \
  -H "Content-Type: application/json" \
  -d '{
    "resourceType": "ServiceRequest",
    "status": "active",
    "intent": "order",
    "priority": "urgent",
    "code": {
      "coding": [{
        "system": "https://openconceptlab.org/orgs/Medtronic-LABS/sources/SPICE",
        "code": "NCD",
        "display": "NCD Referral"
      }],
      "text": "NCD Referral"
    },
    "subject": { "reference": "Patient/ET/ADM/2023/001234" },
    "requester": { "reference": "Practitioner/practitioner-fhir-uuid-chw-101" },
    "authoredOn": "2026-04-22T09:30:00+00:00",
    "patientInstruction": "Uncontrolled Stage 2 Hypertension"
  }' | python3 -m json.tool
```

**Expected response:**
```json
{
  "totalEntries": 1,
  "succeeded": 1,
  "results": [{ "resourceType": "ServiceRequest", "route": "rest", "status": "created", "httpStatus": 201 }]
}
```

---

## Troubleshooting

| Problem | Likely Cause | Fix |
|---------|-------------|-----|
| `Patient reference not resolved` | Patient NID not in OpenMRS | Register patient with NID identifier (§ 7.2) |
| `Concept not found: SPICE code NCD` | NCD concept not mapped | Create concept with SPICE source mapping (§ 5.2) |
| `400 Bad Request from order endpoint` | `orderer` or `concept` UUID invalid | Verify provider and concept UUIDs via OpenMRS REST |
| `orderer not resolved` | Practitioner FHIR ID not matching any provider identifier | Create provider with `identifier = <spice-practitioner-fhir-id>` (§ 4.2) |
| `results[] empty for concept search` | SPICE concept source missing | Create "SPICE" concept source first (§ 5.1) |
| `FHIR parser error: +0000 timezone` | Payload uses `+0000` (no colon) | Use `+00:00` in FHIR payloads |
| `ServiceRequest not seen by emitter` | Emitter pointing to wrong FHIR server | Confirm emitter points to HAPI FHIR (`8090`), not OpenMRS FHIR2 (`9096`) |
