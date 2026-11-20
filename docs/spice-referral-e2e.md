# SPICE → OpenMRS Referral: End-to-End Test Runbook

This document is the **executable, step-by-step runbook** for testing the full SPICE referral
workflow against this OpenMRS O3 reference instance. Every step has the exact `curl` you run
or the exact UI clicks you make, plus an explanation of every UUID referenced.

> **Audience:** developers building/testing the SPICE adaptor.
> **Premise:** SPICE is the upstream system. OpenMRS is the downstream EMR where a clinician
> (Dr John) acts on the inbound referral, then closes the loop back to SPICE.
> **No frontend code changes are required for this flow.** Only REST + UI interaction.

> **Over Beckn (proposal, not built yet).** With `OPENMRS_RECEIVER_BECKN_ENABLED=true`, Spice's referral arrives at the adaptor from `onix-bpp` at `POST /beckn/bpp-webhook/update`, instead of from CCE. The adaptor writes it the same way (Step 1 below is unchanged in effect) and answers Spice with an `Ack` (or a `Nack` with a code), then `on_update` `ACCEPTED`. Once the order is closed (Step 8), the outcome goes back to Spice from the emitter adaptor as `on_status`. The existing `/api/v1/openmrs/fhir` endpoint is unchanged. See [beckn-integration.md](beckn-integration.md).

---

## 0 · Conventions

- Base URL: `http://localhost:9096/openmrs`
- Admin auth: `admin:Admin123` (used for setup curls)
- Clinician (UI): `john / John123` (Dr John, configured earlier)
- All `curl` snippets use **`{{variable}}`** placeholders so they paste directly into Postman. To run them in bash, see [§ 0.1](#01--running-from-bash). To import the ready-made collection, see [§ 0.2](#02--running-from-postman).

### 0.1 · Running from bash

Export the variables once, then `envsubst` each curl on the fly:

```bash
export baseUrl='http://localhost:9096/openmrs'
export patientUuid='9aa95993-fd45-46f8-9f5f-0813e2c72cfb'   # change me
export spiceRef="SPICE-ASMT-$(date +%Y%m%d)-$$"
export nowUtc=$(date -u +%Y-%m-%dT%H:%M:%S.000+0000)
```

Then wrap any curl with `envsubst`:

```bash
echo '<paste curl here>' | envsubst | bash
```

After each step, export the captured UUID:

```bash
export visitUuid='<uuid from 4.1>'
export encounterUuid='<uuid from 4.2>'
export orderUuid='<uuid from 4.3>'
```

### 0.2 · Running from Postman

A ready-made collection lives at [`postman/SPICE-Referral-E2E.postman_collection.json`](../postman/SPICE-Referral-E2E.postman_collection.json).

1. Postman → **Import** → drag the JSON file in
2. Open the collection → **Variables** tab → set `baseUrl`, `patientUuid` (others auto-fill from response Tests scripts)
3. Open the collection → **Authorization** tab → already set to Basic Auth `admin / Admin123`
4. Run requests in order. Each response auto-captures its `uuid` into the next request's variable via the **Tests** script
5. To run end-to-end: Right-click collection → **Run collection** → click Run

---

## 1 · UUID Reference Table

These UUIDs are **fixed** for this reference instance — copy/paste freely.

### Locations & Visits

| UUID | What it is |
|---|---|
| `44c3efb0-2583-4c80-a79e-1f756a03c0a1` | **Outpatient Clinic** location (where the referral lands) |
| `287463d3-2233-4c69-9851-5841a1f5e109` | **OPD** Visit Type (started by the clerk when the patient arrives) |
| *discovered by name* | **Referral Intake** Visit Type — retired; the receiver files the referral in a closed visit of this type (created if missing) |

### Encounter Types

| UUID | What it is | Used in |
|---|---|---|
| `de0f5558-9813-4fa3-8613-467015f9fabd` | **Referral In** *(created today)* | Step 4 — seed encounter |
| `dd528487-82a5-4082-9c72-ed246bd49591` | **Consultation** | Step 7 — Dr John's referral response (until/unless we create a dedicated `Referral Response` type) |
| `67a71486-1a54-468f-ac3e-7091a9a79584` | **Vitals** | Step 6 |
| `d7151f82-c1f3-4152-a605-2f9ea7414a79` | **Visit Note** | Step 7 (alt) |
| `39da3525-afe4-45ff-8977-c53b7b359158` | **Order** | Auto-attached when an Order is included in an Encounter POST |

### Encounter Roles & Providers

| UUID | What it is |
|---|---|
| `240b26f9-dd88-4172-823d-4a8bfeb7841f` | **Clinician** EncounterRole |
| `adf8ca68-847c-40f9-bc11-422a00e9b35b` | **SPICE-SYSTEM** Provider — represents the inbound system itself; this is who "creates" the seed encounter |
| `1237eece-bc8b-44cf-b221-8d3424a1e29e` | **Dr John** Provider (id `7-5`); used for the response encounter |

### Order Types & Concepts

| UUID | What it is |
|---|---|
| `778a9dc6-87d9-49c1-83d9-caa01041a8ad` | **Referral** OrderType |
| `b93912b8-c96e-4453-b8ad-df090eb76e2f` | **External Referral** OrderType (alt — for outbound use) |
| `6f0c9a92-6f24-11e3-af88-005056821db0` | **Outpatient** CareSetting |
| `6e1cc43d-e8ad-4592-bc7a-928dbc090479` | Concept: **ICCM** (the referral reason for our test scenario) |

### Service Queues (at Outpatient Clinic)

| UUID | What it is |
|---|---|
| `d692a223-e140-11ee-bad2-0242ac120002` | **Outpatient Triage** queue |
| `13b656d3-e141-11ee-bad2-0242ac120002` | **Outpatient Consultation** queue |
| `51ae5e4d-b72b-4912-bf31-a17efb690aeb` | Status: **Waiting** |
| `ca7494ae-437f-4fd0-8aae-b88b9a2ba47d` | Status: **In Service** |
| `b559fb77-4e1e-4285-b9b7-1d03e0ba983f` | Status: **Finished** |
| `f4620bfa-3625-4883-bd3f-84c2cce14470` | Priority: **Routine** |
| `04f6f7e0-e3cb-4e13-a133-4479f759574e` | Priority: **Emergency** |

### Test patient (existing)

| UUID | What it is |
|---|---|
| `9aa95993-fd45-46f8-9f5f-0813e2c72cfb` | **Demo Patient** (NID-1774256338) — *replace with a fresh patient UUID for clean runs* |

---

## 2 · Pre-test setup (manual)

Before each run, do this in the UI:

1. Login as `admin` (or `john`) → set location to **Outpatient Clinic**
2. Open the patient you'll test with
3. **End any active visit** for that patient (Actions → End active visit)
   - Reason: a patient can only have one active visit at a time; the seed POST will fail otherwise
4. Note the patient's UUID — you'll inject it into Step 4

> Substitute `PATIENT_UUID` in every following step with the actual UUID.

---

## 3 · The flow at a glance

```
SPICE side                       OpenMRS side                            UI evidence
─────────                        ────────────                            ────────────
Adaptor receives                 Step 4: POST /encounter                Visit appears in chart
   referral payload  ─────────►   ├─ creates Visit                       Encounter appears
                                  ├─ creates Referral In Encounter       Order appears (status=Received)
                                  ├─ creates Referral Order
                                  └─ returns encounter UUID

                                 Step 4b: POST /alert                   Bell icon + top-right toast
                                  (fire-and-forget)                      pop in O3 for resolved
                                  └─ ResourceRouter.fireReferral…       recipients (default: admin
                                                                          static UUID)

                                 Step 5 (manual / OpenMRS UI):          Patient appears in
                                  POST /queue-entry                      Service Queues / Waiting
                                  └─ enqueues at Outpatient Cons.       (the adaptor does NOT
                                                                          create queue entries—
                                                                          see note below)

                                 (Clinician picks up)
                                                                        Dr John logs in,
                                                                        opens chart from queue

                                 Step 6: Vitals form (UI)               Vitals tile populated
                                  └─ creates Vitals encounter + obs

                                 Step 7: Visit Note + Referral
                                          Response form (UI)
                                  ├─ creates Visit Note encounter
                                  ├─ creates Diagnosis row
                                  └─ creates Response encounter + obs

                                 Step 8: POST /order/{uuid}/             Order status flips to
                                          fulfillerdetails                Completed in chart
                                  └─ closes the loop

Adaptor poll picks up           Step 9: GET /encounter?type=             Adaptor reads response
   the response  ◄──────────             Referral Response               Pushes ACK to SPICE
                                                                         End visit (UI)
```

> **Adaptor scope note.** As of the current `demo` build, the **CCE Receiver Adaptor no longer creates `queue-entry` rows**. Step 5 below is preserved as an *operator / test* action so this runbook still maps to the OpenMRS UI you'll see in Service Queues. In production, queue-entry creation is driven either by the OpenMRS Queue module's encounter-event listeners or by the receiving clinician using the Service Queues page — not by this adaptor.

---

## 4 · Step 1: Seed the referral (SPICE adaptor → OpenMRS)

This is **one POST** that creates Visit + Encounter + Order atomically.

### 4.1 Start a visit

```bash
curl -X POST '{{baseUrl}}/ws/rest/v1/visit' \
  -u admin:Admin123 \
  -H 'Content-Type: application/json' \
  -d '{
    "patient":       "{{patientUuid}}",
    "visitType":     "287463d3-2233-4c69-9851-5841a1f5e109",
    "location":      "44c3efb0-2583-4c80-a79e-1f756a03c0a1",
    "startDatetime": "{{nowUtc}}"
  }'
```

In Postman, replace `{{nowUtc}}` with the built-in `{{$isoTimestamp}}` (already done in the collection).

Capture the returned `uuid` → that's `visitUuid` (auto-captured by the Postman Tests script).

### 4.2 Create the Referral In encounter

```bash
curl -X POST '{{baseUrl}}/ws/rest/v1/encounter' \
  -u admin:Admin123 \
  -H 'Content-Type: application/json' \
  -d '{
    "patient":           "{{patientUuid}}",
    "visit":             "{{visitUuid}}",
    "encounterType":     "de0f5558-9813-4fa3-8613-467015f9fabd",
    "location":          "44c3efb0-2583-4c80-a79e-1f756a03c0a1",
    "encounterDatetime": "{{nowUtc}}",
    "encounterProviders": [{
      "provider":      "adf8ca68-847c-40f9-bc11-422a00e9b35b",
      "encounterRole": "240b26f9-dd88-4172-823d-4a8bfeb7841f"
    }]
  }'
```

Capture top-level `uuid` → `encounterUuid`.

#### What just got written

| Table | Row |
|---|---|
| `encounter` | new row, type = Referral In, provider = SPICE-SYSTEM, linked to the visit from 4.1 |
| `encounter_provider` | links Encounter to SPICE-SYSTEM with role Clinician |

### 4.3 Create the Referral Order linked to that encounter

```bash
curl -X POST '{{baseUrl}}/ws/rest/v1/order' \
  -u admin:Admin123 \
  -H 'Content-Type: application/json' \
  -d '{
    "type":            "order",
    "orderType":       "778a9dc6-87d9-49c1-83d9-caa01041a8ad",
    "concept":         "6e1cc43d-e8ad-4592-bc7a-928dbc090479",
    "patient":         "{{patientUuid}}",
    "encounter":       "{{encounterUuid}}",
    "orderer":         "adf8ca68-847c-40f9-bc11-422a00e9b35b",
    "careSetting":     "6f0c9a92-6f24-11e3-af88-005056821db0",
    "urgency":         "STAT",
    "accessionNumber": "{{spiceRef}}",
    "instructions":    "ICCM danger signs - convulsions and vomiting; refer to OpenMRS clinic for emergency care"
  }'
```

Capture:
- `uuid` → `orderUuid`
- `orderNumber` → e.g. `ORD-347` (this is what shows in the UI)

#### What just got written

| Table | Row |
|---|---|
| `orders` | new row, orderType = Referral, concept = ICCM, urgency = STAT, accessionNumber = SPICE-ASMT-..., linked to the Referral In encounter |

#### Why split it (vs one POST)

- **Cleaner failure isolation** — if the order fails (bad concept, invalid orderer, etc.) you still have a clean Encounter you can attach an order to later, instead of losing both
- **Mirrors the adaptor's real shape** — the SPICE adaptor will likely call these endpoints sequentially anyway (and may add Order Attribute writes between them later)
- **Easier to debug** — each response is small and readable

---

## 5 · Step 2: Enqueue the patient

> **Manual step.** The adaptor does **not** create queue entries. Run this `curl` (or use the Service Queues UI) only when you want the patient to show up on the worklist for testing the downstream clinician flow.

This puts the patient on "Outpatient Triage" worklist with **Emergency** priority.

```bash
curl -X POST '{{baseUrl}}/ws/rest/v1/queue-entry' \
  -u admin:Admin123 \
  -H 'Content-Type: application/json' \
  -d '{
    "patient":   "{{patientUuid}}",
    "visit":     "{{visitUuid}}",
    "queue":     "d692a223-e140-11ee-bad2-0242ac120002",
    "status":    "51ae5e4d-b72b-4912-bf31-a17efb690aeb",
    "priority":  "04f6f7e0-e3cb-4e13-a133-4479f759574e",
    "startedAt": "{{nowUtc}}"
  }'
```

Capture `uuid` → `queueEntryUuid`.

### Verify in UI

1. Navigate: **Home → Service queues**
2. Filter by location = Outpatient Clinic
3. You should see your patient with **Waiting** status, **Emergency** priority, queue **Outpatient Consultation**

---

## 6 · Step 3 (optional): Triage transition

If your workflow includes Triage first, the triage nurse can transition the entry through the Service Queues UI (Actions → Transition). For SPICE referrals you typically skip Triage and go straight to Consultation, which is what Step 5 already does.

---

## 7 · Step 4: Dr John picks up the patient

Pure UI:

1. Login as `john / John123`
2. **Service queues** page → click the patient row → opens patient chart
3. Verify the patient banner shows **Active visit** badge

---

## 8 · Step 5: Capture vitals

Pure UI:

1. Patient chart → right-rail pencil icon → **Vitals**
2. Enter at least: BP 110/70, Temp 37.0, HR 80
3. Save

### What got written

| Table | Row |
|---|---|
| `encounter` | new row, type = Vitals, provider = Dr John |
| `obs` | one row per vital captured (BP systolic, BP diastolic, temperature, pulse, etc.) |

### Verify

```bash
curl -X GET '{{baseUrl}}/ws/rest/v1/encounter?patient={{patientUuid}}&encounterType=67a71486-1a54-468f-ac3e-7091a9a79584&v=custom:(uuid,encounterDatetime,obs:(concept:(display),value))' \
  -u admin:Admin123
```

---

## 9 · Step 6: Visit Note + Diagnosis

Pure UI:

1. Patient chart → pencil icon → **Visit Note**
2. Primary diagnosis: search "Acute gastroenteritis" → select
3. Note text: `"Confirmed ICCM. ORS started, mother counseled. Discharge after 2h observation."`
4. Save and close

### What got written

| Table | Row |
|---|---|
| `encounter` | type = Visit Note, provider = Dr John |
| `obs` | Text of encounter note + Visit Diagnosis obs |
| `diagnosis` | one row, primary, confirmed, linked to encounter |

---

## 10 · Step 7: Fill the Referral Response form

Pure UI:

1. Patient chart → right-rail page-icon → **All forms**
2. Open **Referral Response**
3. Fill: Final diagnosis (autocompletes from dictionary), Was assessment confirmed?, Clinical addendum, Follow-up needed?
4. Save

### What got written

| Table | Row |
|---|---|
| `encounter` | type = Consultation *(today)* — will become Referral Response when we create that type |
| `obs` | one row per filled question |
| `diagnosis` | if a diagnosis was added, one row |

This is the encounter your SPICE adaptor will watch for to know "the loop is ready to close."

---

## 11 · Step 8: Close the order (mimics what the adaptor does)

Until the SPICE adaptor is wired, do this manually:

```bash
curl -X POST '{{baseUrl}}/ws/rest/v1/order/{{orderUuid}}/fulfillerdetails' \
  -u admin:Admin123 \
  -H 'Content-Type: application/json' \
  -d '{
    "fulfillerStatus":  "COMPLETED",
    "fulfillerComment": "Patient seen, treated, response recorded"
  }'
```

### Verify in UI

Patient chart → **Orders** widget → expand ORD-347 row → status pill changes from **Received** to **Completed**.

### Verify via REST

```bash
curl -X GET '{{baseUrl}}/ws/rest/v1/order/{{orderUuid}}?v=custom:(uuid,orderNumber,fulfillerStatus,fulfillerComment)' \
  -u admin:Admin123
```

---

## 12 · Step 9: End the visit (UI)

1. Patient chart → **Actions** → **End active visit**
2. Visit moves to "Past visits"
3. All open queue entries on this visit auto-close

---

## 13 · Verification one-liners

Run these any time to confirm the full chain exists.

### a. List today's referrals on this patient

```bash
curl -X GET '{{baseUrl}}/ws/rest/v1/order?patient={{patientUuid}}&orderType=778a9dc6-87d9-49c1-83d9-caa01041a8ad&v=custom:(uuid,orderNumber,urgency,accessionNumber,fulfillerStatus,dateActivated)' \
  -u admin:Admin123
```

### b. List all encounters on the visit

```bash
curl -X GET '{{baseUrl}}/ws/rest/v1/encounter?patient={{patientUuid}}&v=custom:(uuid,encounterDatetime,encounterType:(name),encounterProviders:(provider:(display)))' \
  -u admin:Admin123
```

### c. Queue entries

```bash
curl -X GET '{{baseUrl}}/ws/rest/v1/queue-entry?patient={{patientUuid}}&v=custom:(uuid,queue:(name),status:(display),priority:(display),startedAt,endedAt)' \
  -u admin:Admin123
```

### d. SQL — full audit (run from inside the MariaDB container)

```sql
SELECT v.visit_id, v.uuid AS visit_uuid, vt.name AS visit_type,
       v.date_started, v.date_stopped
FROM visit v
JOIN visit_type vt ON vt.visit_type_id = v.visit_type
WHERE v.patient_id = (SELECT patient_id FROM patient_identifier
                      WHERE uuid = (SELECT uuid FROM patient
                                    WHERE patient_id =
                                      (SELECT person_id FROM person
                                       WHERE uuid = '<PATIENT_UUID>')))
ORDER BY v.date_started DESC LIMIT 5;
```

---

## 14 · Expected end state

After all 9 steps complete, this patient/visit owns:

| Artifact | Count | Type |
|---|---|---|
| Visit | 2 | Referral Intake (closed when the referral arrived), OPD (started on arrival, ended) |
| Encounter | 4–5 | Referral In, Vitals, Visit Note, Consultation (response) |
| Obs | 8–15 | Vitals + diagnoses + response answers |
| Diagnosis | 1–2 | Final dx |
| Order | 1 | Referral, fulfillerStatus = COMPLETED |
| Queue Entry | 1 | Outpatient Consultation, Finished |

---

## 15 · Known gaps & next steps

| Gap | Status | Resolution path |
|---|---|---|
| Referral Response uses Consultation type | Tracked | Create `Referral Response` EncounterType, update form's `encounterType` field |
| `fulfillerStatus` is closed manually with curl | Tracked | Adaptor poll, or `openmrs-eip` Camel route, or form `postSubmitAction` |
| Referral Response form lacks structured fields | Tracked | Add gating "Did patient present?" question backed by custom Coded concept |
| No referrals fulfillment dashboard like Lab/Pharmacy | Documented as won't-fix | Use Service Queues; no other distro has one either |
| Order has no SPICE External Reference attribute | Tracked | Create OrderAttributeTypes (deferred) |
| No outbound ACK back to SPICE in this doc | Out of scope here | Adaptor responsibility |

---

## 16 · Tear-down for the next run

The cleanest reset is:

1. End the active visit (UI: Actions → End active visit)
2. Optionally void the order via REST:
   ```bash
   curl -X DELETE '{{baseUrl}}/ws/rest/v1/order/{{orderUuid}}?reason=test+cleanup' \
     -u admin:Admin123
   ```
3. Optionally void the encounters:
   ```bash
   curl -X DELETE '{{baseUrl}}/ws/rest/v1/encounter/{{encounterUuid}}?reason=test+cleanup' \
     -u admin:Admin123
   ```

For a true clean slate, use a fresh patient instead of voiding.
