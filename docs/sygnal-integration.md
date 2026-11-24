# Sygnal Integration — OpenMRS CCE Receiver Adaptor

**Status:** Built for the demo. On by default on this branch (`OPENMRS_RECEIVER_BECKN_ENABLED=true`); set `OPENMRS_RECEIVER_BECKN_ENABLED=false` to switch it off.
**Contract:** the CCE profile of Sygnal, `cce-beckn-service/schema/sygnal-profile/v3.0/`: `cce-sygnal-apis.yaml` (Sygnal's types as Sygnal publishes them, opened) and `cce-fhir-extension.yaml` (the FHIR fields and CCE's rules, which the first refers to). The sender is `cce-beckn-service` (`MessageFactory#signalEmit`).

The receiver gets one Sygnal endpoint, `POST /sygnal/signal/emit`. CCE sends its care alerts there, and the receiver raises them as OpenMRS notifications. It is an extra door into OpenMRS, not a replacement: the existing `POST /api/v1/openmrs/fhir` endpoint is unchanged, and with `OPENMRS_RECEIVER_BECKN_ENABLED=false` the receiver behaves exactly as before Beckn.

> **Change 2026-11-23: extended Sygnal types (profile v3.0).** The `message` of `sygnal/signal/emit` is a `sygnal:Signal` with the care-alert fields directly on it (§3.1): `patientId`, `stepStatus`, `slaStatus`, `fhirVersion` and `resource`. Its `severity` is CCE's level (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`). There is no `signalAttributes` bag and no `cce:CceCareAlert` type in the message. (Since 2026-11-24 CCE's level is in a field of its own, `cceSeverity`, and `severity` is not sent: see the change of that date below.)
>
> The customer decided this on 2026-11-23: to open and extend CCE's copy of `sygnal:Signal` (profile v3.0), instead of sending the `cce:CceCareAlert` message (profile v2.0). Sygnal's published schema is not changed. Every participant of this network loads CCE's copy, so the messages validate; a validator that uses Sygnal's published schema would refuse them.
>
> Why: `sygnal:CoordinationEvent`, the payload of the other action (`sygnal/event/report`), has no place for a FHIR resource as published, and it is closed (`additionalProperties: false`). So it could not carry one without a change. The customer chose to make that change in CCE's copy (the type is open, and the needed fields are declared on it) and to extend `sygnal:Signal` the same way. This avoids new CCE types for the Sygnal messages.
>
> History:
>
> | Profile | Date | `message` of `sygnal/signal/emit` | Here |
> |---|---|---|---|
> | v1.0 | 2026-11-20 | `sygnal:Signal` with the alert in a `signalAttributes` bag | Refused: 400 `SCH_REQUIRED_FIELD_MISSING` (the alert fields in the bag are not read, so the Signal has no `patientId`) |
> | v2.0 | 2026-11-23 | The `cce:CceCareAlert` itself (merged into this branch with PR #23) | Refused: 400 `SCH_TYPE_NOT_SUPPORTED` |
> | v3.0 | 2026-11-23 | `sygnal:Signal` with the care-alert fields on it | The one in use |
>
> Unchanged through all three: the action name and path, the Sygnal `context`, the `Ack` / `Nack` bodies and codes, no callbacks, the onix routing, the ids, every setting, and the Spice referral flows. The description at the top of the profile file has the full list of differences from Sygnal.
>
> **Change 2026-11-23 (later the same day).** Profile v3.0 is now two files in one folder: `cce-sygnal-apis.yaml` (Sygnal's own fields; the types are open, `additionalProperties: true`, and `Signal.severity` also accepts `LOW` / `MEDIUM` / `HIGH`) and `cce-fhir-extension.yaml` (`SignalFhirExtension`, `CoordinationEventFhirExtension`: the FHIR fields), which the first refers to. The messages on the wire are unchanged. `cce:CceCareAlert` is no longer used anywhere and its schema (`CceCareAlert/v1.0`) is deleted. The notification key prefix is now `SygnalSignal:` (§4). In the Spice referral messages the `performanceAttributes` box is now a `sygnal:CoordinationEvent` ([beckn-integration.md](beckn-integration.md) §3.3); the receiver handles them as before.
>
> **Change 2026-11-24: Sygnal's types as Sygnal publishes them; CCE's level of severity in `cceSeverity`.** The customer decided this on 2026-11-24. `cce-sygnal-apis.yaml` now holds `sygnal:Signal` and `sygnal:CoordinationEvent`, and the types they use (`Participant`, `HashRef`, `Area`, `Attributes`), word for word as Sygnal v0.9.0 publishes them, with one change: the two types are open (`additionalProperties: true`). The earlier changes inside the types are gone from that file: `Signal.severity` takes only Sygnal's `INFO`, `WARNING`, `CRITICAL` again. CCE's rules are now in `cce-fhir-extension.yaml`, which can add fields and make rules stricter but not loosen them. `SignalFhirExtension` requires `id` (a UUID), `target` and `cceSeverity`, next to the care-alert fields (§3.3).
>
> So an alert carries CCE's level of severity only in a field of its own on the Signal, like the other care-alert fields (§3.1):
>
> | Field | Holds |
> |---|---|
> | `cceSeverity` | CCE's own level: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. Named so that it does not clash with Sygnal's `severity` on the same object |
> | `severity` | Not sent. Sygnal does not require it (its required fields are `@type`, `signalType`, `issuedAt`), and CCE's extension no longer requires it. If another sender includes it, it must be one of Sygnal's `INFO`, `WARNING`, `CRITICAL` |
>
> There is no mapping from CCE's levels to Sygnal's, and no `signalAttributes` bag is sent. (Earlier on 2026-11-24 CCE also sent `severity` with a Sygnal level mapped from its own, `CRITICAL` → `CRITICAL`, `HIGH` and `MEDIUM` → `WARNING`, `LOW` → `INFO`; that was dropped the same day. Later that day CCE's level was for a while in Sygnal's bag, `signalAttributes.severity` with `@type` `cce:CareAlertAttributes`; it moved to `cceSeverity` so that the Signal is extended the same way as the clinical event, with every CCE field directly on it. In `cce-beckn-service` the extended Signal now has its own schema folder, `schema/Signal/v3.0`, like `CoordinationEvent/v3.0`.)
>
> The receiver reads CCE's level only from `cceSeverity`; it reads neither `message.severity` nor a `signalAttributes` bag (§4). The notification severity is the same as before for every CCE level. The log line's `code` keeps CCE's level (§8.2). The rest of the message is unchanged.
>
> Checked through three onix 1.9.4 adapters with the real `cce-beckn-service`, 44 of 44 cases (with the bag, before the move to `cceSeverity`): an alert without `Signal.severity` is accepted; a CCE level such as `HIGH` in `Signal.severity` is refused by Sygnal's enum.

Only the CCE care-alert leg uses Sygnal. It replaces the earlier Beckn `on_status` care alert on `/beckn/bap-webhook`. The Spice ↔ OpenMRS referral flows (`/beckn/bpp-webhook/{update,select,init,confirm,status}`, the callbacks to Spice, and Spice's `on_update` / `on_status` on `/beckn/bap-webhook`) stay standard Beckn, and the receiver handles them as before; since 2026-11-23 their `performanceAttributes` box is a `sygnal:CoordinationEvent`: see [beckn-integration.md](beckn-integration.md).

---

## 1. The Two Flows

```
---- OpenMRS to CCE (not this service) ----
openmrs-emitter-adaptor ─► onix-openmrs ══ sygnal/event/report ══► onix-openhim ─► openhim-emitter-adaptor ─► OpenHIM
                                                                                                             │
                                  cce-beckn-service ◄─ onix-cce ◄══ sygnal/event/report ══ onix-openhim ◄────┘

---- CCE to OpenMRS (this service) ----
CCE intelligence ─► cce-beckn-service ─► onix-cce ══ sygnal/signal/emit ══► onix-openmrs ─► openmrs-cce-receiver-adaptor ─► OpenMRS notification
```

══ marks a Sygnal hop: signed by the sender's onix and checked by the receiver's onix. ─ marks plain HTTP. There are no callbacks: the synchronous HTTP answer is the whole result of each message.

| Participant | Subscriber id (`senderId` / `receiverId`) | onix container |
|---|---|---|
| CCE | `bpp.cce.nextsteps.mdtlabs.org` | `onix-cce` |
| HIE | `bap.openhim.nextsteps.mdtlabs.org` | `onix-openhim` |
| OpenMRS | `bap.openmrs.nextsteps.mdtlabs.org` | `onix-openmrs` |

---

## 2. The Receiver's Part

```
onix-openmrs ── /sygnal/receiver/sygnal/signal/emit ──► POST /sygnal/signal/emit ──► 200 Ack / 4xx-5xx Nack
                                                                │
                                                                └─(after the Ack, own thread)─► CareAlertNotifier ──► OpenMRS /alert
```

What stays the same:

- `POST /api/v1/openmrs/fhir` and its answers.
- The write path: `BundleSplitter`, `ResourceRouter`, the referral handling (closed "Referral Intake" visit, "Referral In" encounter, Referral order, bell notification).
- The Spice referral endpoints under `/beckn/**` ([beckn-integration.md](beckn-integration.md)).
- The receiver keeps no state between messages, except the notification client's de-duplication memory. No database, no queue.

Nothing is written into OpenMRS by the Sygnal endpoint except the notification.

---

## 3. Endpoint

`POST /sygnal/signal/emit` exists only when `receiver.beckn.enabled=true`, the same switch as the Beckn referral endpoints. With it off, no Sygnal or Beckn bean is created and the path answers 404.

It is called by `onix-openmrs`. onix receives the signal at `https://<public-host>/sygnal/receiver/sygnal/signal/emit`, checks the signature and the schema, and forwards the body unchanged to `http://openmrs-cce-receiver-adaptor:8080/sygnal/signal/emit` (the action is the path).

### 3.1 The message

The body is exactly what `cce-beckn-service` builds:

```json
{
  "context": {
    "version": "2.0.0", "action": "sygnal/signal/emit", "networkId": "<network id>",
    "senderId": "bpp.cce.nextsteps.mdtlabs.org", "receiverId": "bap.openmrs.nextsteps.mdtlabs.org",
    "receiverUri": "https://<openmrs public host>/sygnal/receiver",
    "transactionId": "<uuid, new per alert>", "messageId": "<delivery id>", "timestamp": "2026-11-20T10:00:00.123Z"
  },
  "message": {
    "@type": "sygnal:Signal",
    "id": "<delivery id>",
    "signalType": "SLA_BREACH",
    "target": { "id": "bap.openmrs.nextsteps.mdtlabs.org", "role": "PROVIDER" },
    "basis": "CCE care step status: not-started, SLA status: overdue",
    "issuedAt": "2026-11-20T10:00:00.123Z",
    "cceSeverity": "HIGH",
    "patientId": "260225-0002-5501",
    "stepStatus": "not-started",
    "slaStatus": "overdue",
    "fhirVersion": "4.0.1",
    "resource": { "resourceType": "CommunicationRequest", "...": "..." }
  }
}
```

The `message` is a `sygnal:Signal` as the profile extends it (§3.3). CCE's level of severity is `cceSeverity` (here `HIGH`); CCE does not send Sygnal's `severity`, nor a `signalAttributes` bag. The message carries no `@context`. `context.messageId` and `message.id` are both the intelligence service's delivery id. A repeated alert carries the same id.

### 3.2 Checks

In this order. The first one broken gives a `Nack` (400) with the code shown; nothing is notified.

| Check | Code |
|---|---|
| The body is a JSON object | `SCH_INVALID_JSON` |
| `context` is present | `CTX_MISSING_FIELD` |
| `context.action` is present / is `sygnal/signal/emit` | `CTX_MISSING_FIELD` / `CTX_ACTION_MISMATCH` |
| `context.receiverId` is present / is `receiver.beckn.self.id` | `CTX_MISSING_FIELD` / `CTX_INVALID_FIELD` |
| `context.senderId` is present / is `receiver.beckn.cce.id` | `CTX_MISSING_FIELD` / `CTX_INVALID_FIELD` |
| `context.messageId` is present | `CTX_MISSING_FIELD` |
| `message` is present | `SCH_REQUIRED_FIELD_MISSING` |
| `message.@type` is `sygnal:Signal` | `SCH_TYPE_NOT_SUPPORTED` |
| `message.id` is present | `SCH_REQUIRED_FIELD_MISSING` |
| `message.patientId` is present | `SCH_REQUIRED_FIELD_MISSING` |
| `message.resource` has a `resourceType` | `SCH_REQUIRED_FIELD_MISSING` |

Any other `message.@type` is refused, the `cce:CceCareAlert` of profile v2.0 included. The receiver does not read a `signalAttributes` bag: a Signal of profile v1.0, with the alert only in that bag, has no `patientId` of its own and is refused for that. The receiver does not check the levels; onix does, against the profile. A field the receiver does not know is ignored.

onix has already validated the schema, so in normal operation only the id checks can fail (a misrouted signal, or the wrong `OPENMRS_RECEIVER_BECKN_*_ID`), or the type and field checks while a sender still uses an older profile.

### 3.3 The extended `sygnal:Signal`

The fields of `message` that CCE sends. The first seven are Sygnal's own, as Sygnal publishes them; the ones from `patientId` down are added by CCE's extension (`SignalFhirExtension` in `cce-fhir-extension.yaml`). Sygnal requires only `@type`, `signalType` and `issuedAt`; the extension also requires `id` (a UUID), `target` and `cceSeverity` (since 2026-11-24). CCE does not send Sygnal's `severity`; if another sender includes it, it must be one of Sygnal's `INFO`, `WARNING`, `CRITICAL`. The type is open in the profile, so onix does not refuse an extra field, and the receiver ignores it.

| Field | Required | Use |
|---|---|---|
| `@type` | Yes | `sygnal:Signal` |
| `id` | Yes | The delivery id, a UUID; the same as `context.messageId`. The de-duplication key when the resource has no delivery id identifier (§4) |
| `signalType` | Yes | `SLA_BREACH` when `slaStatus` is `overdue`, `missed` or `breached`, else `FOLLOW_UP_DUE`. Not read by the receiver |
| `target` | Yes | `{ "id": <context.receiverId>, "role": "PROVIDER" }`. Not read by the receiver |
| `basis` | No | A sentence with the step status and the SLA status. Not read by the receiver |
| `issuedAt` | Yes | When CCE sent the alert. Not read by the receiver |
| `cceSeverity` | Yes | CCE's own level of severity: `LOW`, `MEDIUM`, `HIGH`, `CRITICAL`. Not Sygnal's `severity`, which CCE does not send |
| `patientId` | Yes | The patient's national id (the id CCE uses) |
| `stepStatus` | Yes | The step's status, e.g. `not-started`, `completed` |
| `slaStatus` | No | The SLA status, e.g. `overdue`, `missed` |
| `fhirVersion` | Yes | Always `4.0.1` |
| `resource` | Yes | The FHIR `CommunicationRequest` or `Task`: the alert text in `payload[0].contentString` (`Task.description`), the delivery id in `identifier` (system `http://openphc.org/fhir/intelligence-delivery-id`), the protocol step in the `cce-protocol-step-id` extension |

### 3.4 `Ack` and `Nack` bodies

The same shape as `cce-beckn-service`. Both keep the request's `context.messageId`; a `messageId` that is not a UUID is replaced by a new one, because the schema requires a UUID.

`Ack`, HTTP 200 — the alert is accepted (also a repeat of one already notified):

```json
{ "message": { "status": "ACK", "messageId": "b2c3d4e5-0001-4000-b000-000000000001" } }
```

`Nack`, HTTP 4xx or 5xx:

```json
{
  "message": {
    "status": "NACK",
    "messageId": "b2c3d4e5-0001-4000-b000-000000000001",
    "error": { "code": "CTX_INVALID_FIELD", "message": "context.receiverId is 'x', but OpenMRS is 'bap.openmrs.nextsteps.mdtlabs.org'" }
  }
}
```

| HTTP | Code | When | What the sender should do |
|---|---|---|---|
| 400 | `CTX_*`, `SCH_*` (§3.2) | The request is wrong | Fix it. Sending it again unchanged will not help |
| 503 | `NET_INTERNAL_ERROR` | The notification queue is full | Send again later |
| 500 | `NET_INTERNAL_ERROR` | An unexpected error here | Send again later |

---

## 4. CCE Care Alerts

After a valid request, the receiver hands the alert to the `careAlertExecutor` thread pool and answers `Ack` at once. On that thread it raises an OpenMRS notification through the same `/alert` endpoint and to the same recipients as "New referral received" (`openmrs.notification.*`):

| Notification | From the alert |
|---|---|
| `type` | `CCE_ALERT` |
| `severity` | From CCE's level, `message.cceSeverity`; `message.severity` and a `signalAttributes` bag are not read. `CRITICAL` → `CRITICAL`; `HIGH`, `MEDIUM` → `WARNING`; `LOW` → `INFO`. Without `cceSeverity` there is no CCE level: `INFO`. These are the OpenMRS notification's own levels, the same as before 2026-11-24 for every CCE level |
| `title` | The alert text (cut to 120 characters), else "Care coordination alert" |
| `body` | `Patient national-id <patientId> · Step status: <stepStatus> · SLA status: <slaStatus> · Step: <protocol step>`; parts without a value are left out; "(not registered in OpenMRS)" after the id when no OpenMRS patient has it |
| `link` | The patient's chart, when a patient has that national id |

**De-duplication.** The notification is keyed `SygnalSignal:<delivery id>` (until 2026-11-23 `CceCareAlert:<delivery id>`), where the delivery id is the resource's `intelligence-delivery-id` identifier, else `message.id`, else `context.messageId` (CCE sets all three to the delivery id; a `Task` has no identifier). The prefix of the key is only a name; it is not the message type. The notification client drops a second notification with the same key within `OPENMRS_NOTIFICATION_DEDUPE_TTL_HOURS` (24 h by default). A repeated alert is therefore answered `Ack` and not notified twice.

A failure to notify (no recipients, OpenMRS down) is logged and counted (`openmrs_beckn_care_alerts_total{result="not-notified"}`) but cannot change the answer, which was already sent.

---

## 5. Security: Why `/sygnal/**`

`SecurityConfig`:

- With `cce.security.enabled=true` and a non-blank `cce.security.token`, a `BearerTokenFilter` checks every path that starts with `/api/`. It expects `Authorization: Bearer <token>` and answers 401 otherwise. `/actuator/health` and `/actuator/info` are always open.
- With security off, or with a blank token, every path is open.

onix forwards the network's `Authorization: Signature …` header. Under `/api/**` every signal would get 401 whenever security is on. So the endpoint lives under `/sygnal/**`, and `SecurityConfig` permits `/sygnal/**` explicitly, in the rules and in the token filter, so that a later change to the filter's path rule cannot catch it by accident.

| Path | Checked by |
|---|---|
| `/api/**` | The bearer token, when `CCE_SECURITY_ENABLED=true` |
| `/beckn/**` | The Beckn signature (Spice referrals), checked by `openmrs-onix`. Unchanged |
| `/sygnal/**` | The Sygnal signature, checked by `onix-openmrs` before it forwards. No token. Plus the `senderId` / `receiverId` checks (§3.2) |
| `/actuator/health`, `/actuator/info` | Nothing |

`/sygnal/**` must be reachable only from `onix-openmrs`, inside the OpenMRS network. Do not publish it through a public proxy; publish onix's `/sygnal/receiver/` instead.

---

## 6. Settings

```yaml
receiver:
  beckn:
    enabled: ${OPENMRS_RECEIVER_BECKN_ENABLED:true}
    self:
      id: ${OPENMRS_RECEIVER_BECKN_SELF_ID:bap.openmrs.nextsteps.mdtlabs.org}
    cce:
      id: ${OPENMRS_RECEIVER_BECKN_CCE_ID:bpp.cce.nextsteps.mdtlabs.org}
```

| Property | Env Var | Default | Description |
|---|---|---|---|
| `receiver.beckn.enabled` | `OPENMRS_RECEIVER_BECKN_ENABLED` | `true` | Turns `POST /sygnal/signal/emit` and the Beckn referral endpoints on. Off → 404 |
| `receiver.beckn.self.id` | `OPENMRS_RECEIVER_BECKN_SELF_ID` | `bap.openmrs.nextsteps.mdtlabs.org` | OpenMRS's subscriber id. A signal must carry it as `context.receiverId` |
| `receiver.beckn.cce.id` | `OPENMRS_RECEIVER_BECKN_CCE_ID` | `bpp.cce.nextsteps.mdtlabs.org` | CCE's subscriber id. A signal must carry it as `context.senderId` |

These two ids sit in the same `receiver.beckn` block as the Spice referral settings (`self.bpp-id`, `self.bpp-uri`, `self.network-id`, `onix.bpp-caller-url`, `spice.*`, `health-id-types`; [beckn-integration.md §7](beckn-integration.md#7-settings)). With the endpoints on, the receiver does not start when any required one is blank; the error names every missing one. The Sygnal endpoint sends nothing on the network, so it uses no onix URL. `CCE_SECURITY_ENABLED` and `CCE_SECURITY_TOKEN` keep their meaning, for `/api/**` only. The notification settings (`OPENMRS_NOTIFICATION_*`) apply to care alerts as to every other notification.

---

## 7. Deployment and onix Routing

`onix-openmrs` runs two Sygnal modules on port 8082: `/sygnal/receiver/` (inbound) and `/sygnal/caller/` (used by the emitter adaptor to send `sygnal/event/report`). Only the receiver module concerns this endpoint. The Beckn referral routing to `/beckn/bpp-webhook` and `/beckn/bap-webhook` for Spice is unchanged ([beckn-integration.md §8](beckn-integration.md#8-deployment-and-onix-routing)).

| onix module | Routes | To |
|---|---|---|
| `/sygnal/receiver/` | `sygnal/signal/emit` | `http://openmrs-cce-receiver-adaptor:8080` — onix appends the action, so the adaptor gets `POST /sygnal/signal/emit` |

CCE addresses OpenMRS with `context.receiverUri` = OpenMRS's public onix receiver base (e.g. `https://openmrs.example.org/sygnal/receiver`); `onix-cce` appends the action. That URI is a setting of `cce-beckn-service` (its HMIS list), not of this service.

**Schema.** The Sygnal modules of onix validate `message` against the `Signal` type of the profile, `schema/sygnal-profile/v3.0/` in `cce-beckn-service`. The module loads `cce-sygnal-apis.yaml` as its spec, and `cce-fhir-extension.yaml`, which it refers to, must sit in the same folder. These two files are all they need: there are no payload schemas to load, the extended schema check is off, and the Sygnal modules have no schema folder of their own. No object in the alert carries an `@context` (CCE's level is `cceSeverity`, on the Signal itself, since 2026-11-24); the two profile files check the whole message. Use the same profile version in every onix (v3.0; v1.0 and v2.0 are deleted). The Beckn referral modules have their own schema set: since 2026-11-23 `CoordinationEvent/v3.0` plus the Health* pack ([beckn-integration.md](beckn-integration.md) §8).

**Wait.** The receiver answers before the notification is raised, so the answer is fast; onix's default wait is enough.

On the dev host:

| Container | Container port | Host port | Network |
|---|---|---|---|
| `openmrs-cce-receiver-adaptor` | 8080 | 8097 | `cce-net` |
| `onix-openmrs` | 8082 | per compose file (8082 is taken by `cce-openhim-core` on the dev host) | `cce-net` |

---

## 8. Implementation

Classes under `org.openphc.cce.receiver`. Every Sygnal bean is created only when `receiver.beckn.enabled=true` (`@ConditionalOnBecknEnabled`, `BecknConfig`).

| Class | Package | Job |
|---|---|---|
| `SygnalController` | `controller` | `POST /sygnal/signal/emit` |
| `SignalEmitService` | `beckn` | The checks (§3.2), the answer, hands the alert to the executor |
| `CareAlertNotifier` | `beckn` | Turns the alert into an OpenMRS notification (§4) |
| `BecknMessages` | `beckn` | Builds `Ack` and `Nack` (shared with the Beckn endpoints) |
| `ErrorCodes`, `BecknException` | `beckn` | The canonical Beckn error codes used by this endpoint (the Beckn referral endpoints keep their own `BecknCodes`); a request answered with a `Nack` |
| `BecknMetrics`, `BecknMessageLog` | `beckn` | §8.1, §8.2 |
| `BecknProperties`, `BecknConfig` | `config` | Bind and check `receiver.beckn.*`; the `careAlertExecutor` thread pool (2–4 threads, queue 200), separate from the `becknCallbackExecutor` that sends the Spice callbacks |

### 8.1 Metrics

Exposed at `GET /actuator/prometheus` with the prefix `openmrs_beckn_`. Label values taken from messages are cut to 100 characters. The counters are shared with the Beckn endpoints; signals have `action="sygnal/signal/emit"`.

| Metric | Type | Labels | Description |
|---|---|---|---|
| `openmrs_beckn_messages_received_total` | Counter | `peer` (`context.senderId`), `action`, `result` (`ack`, `nack`) | Signals received, and the answer given |
| `openmrs_beckn_care_alerts_total` | Counter | `result` (`notified`, `not-notified`, `dropped`) | Accepted alerts and whether an OpenMRS notification was raised. `dropped`: the queue was full (answered 503) |

### 8.2 Logging

One line per signal received. Logger `org.openphc.cce.receiver.beckn.messages` (shared with the Beckn lines, which start with `beckn`), in `key=value` form, WARN for a `Nack`:

```
sygnal direction=IN action=sygnal/signal/emit peer=bpp.cce.nextsteps.mdtlabs.org transactionId=a41f… messageId=b2c3… signalId=b2c3… resourceType=CommunicationRequest code=HIGH/not-started/overdue result=ACK
sygnal direction=IN action=sygnal/signal/emit peer=bpp.cce.nextsteps.mdtlabs.org transactionId=a41f… messageId=b2c3… result=NACK code=CTX_INVALID_FIELD error="context.receiverId is …"
```

| Field | Meaning |
|---|---|
| `peer` | `context.senderId` |
| `transactionId`, `messageId` | ids from `context`; `messageId` is the delivery id |
| `signalId` | `message.id`, on an accepted alert; the delivery id again |
| `resourceType` | `CommunicationRequest` or `Task` |
| `code` | On `ACK`: CCE's level of severity (`cceSeverity`, as in §4; empty when missing) / step status / SLA status. On `NACK`: the error code |
| `result` | `ACK` or `NACK` |
| `error` | Only on a `Nack` |

Never logged: message bodies, the FHIR resource, the patient id.

---

## 9. Limits

| Topic | Now |
|---|---|
| Acting on an alert | Not built: the notification only informs. No task or order is opened |
| Alert after a full queue | Answered 503 `Nack`. The contract treats 5xx as "send again"; check that the sender does (see `cce-beckn-service` `AdapterResult#isPermanentRefusal`) |
| Old CCE `on_status` alerts | An `on_status` on `/beckn/bap-webhook` carrying a `cce:CceCareAlert` is logged and acked as a plain callback; no notification |
| Alerts of an older profile | A `sygnal/signal/emit` whose `message` is a `cce:CceCareAlert` (profile v2.0) is refused: 400 `Nack` `SCH_TYPE_NOT_SUPPORTED`. One whose `message` is a `sygnal:Signal` with the alert only in `signalAttributes` (profile v1.0) is refused: 400 `Nack` `SCH_REQUIRED_FIELD_MISSING`. No notification in either case. `cce-beckn-service` and every onix must be on profile v3.0 |
| `message.id` and `context.messageId` | The contract says they are equal. The receiver does not compare them; both are in the log line (§8.2) |
| Spice referrals | Standard Beckn, handled as before; the box is a `sygnal:CoordinationEvent` since 2026-11-23 ([beckn-integration.md](beckn-integration.md)) |

## 10. Related Documents

- [Beckn Integration](beckn-integration.md) — Spice referrals
- [Architecture](architecture.md)
- [API Reference](api-reference.md)
- [Configuration Guide](configuration-guide.md)
- [Deployment Guide](deployment-guide.md)
- [Operations Runbook](operations-runbook.md)
- [OpenMRS Notification Integration](openmrs-notification-integration.md)
