# CCE Receiver Adaptor

A Spring Boot middleware that receives FHIR R4 payloads (Bundles or standalone resources) from upstream systems (RHIE / SPICE / eBuzima), transforms them into OpenMRS REST v1 payloads, and routes them into an OpenMRS instance using a **REST-first** strategy.

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.4.1 |
| HAPI FHIR | 7.4.0 (R4) |
| Gradle | Wrapper (Groovy DSL) |


## Architecture

```
HTTP POST /api/v1/openmrs/fhir  (FHIR R4 JSON)
       │
       ▼
  BundleSplitter ─── splits Bundle into individual resources
       │
       ▼
  ResourceRouter ─── dependency ordering + REST-first routing
       │
       ├──▶ OpenMrsRestClient (primary)
       │      ReferenceResolver → ConceptResolver → RequiredFieldEnricher
       │      → PatientIdentifierEnricher → VisitManager → FhirToRestTransformer
       │      → POST/PUT to OpenMRS REST v1
       │
       └──▶ OpenMrsFhirClient (fallback for Task, DiagnosticReport, etc.)
              → POST/PUT to OpenMRS FHIR2 R4
```

### Supported FHIR Resource Types

| Resource Type | OpenMRS Route | REST Endpoint |
|---------------|---------------|---------------|
| Patient | REST | `/patient` |
| Encounter | REST | `/encounter` |
| Observation | REST | `/obs` |
| Condition | REST | `/condition` |
| AllergyIntolerance | REST | `/allergy` |
| Location | REST | `/location` |
| Practitioner | REST | `/provider` |
| Medication | REST | `/drug` |
| ServiceRequest | REST | `/order` (testorder) |
| MedicationRequest | REST | `/order` (drugorder) |
| Immunization | REST | `/obs` (obs group) |
| Task | FHIR | `/ws/fhir2/R4/Task` |
| DiagnosticReport | FHIR | `/ws/fhir2/R4/DiagnosticReport` |
| Procedure | FHIR | `/ws/fhir2/R4/Procedure` |

## Prerequisites

- Java 21
- Docker & Docker Compose
- A running OpenMRS O3 instance

## Quick Start

### 1. Build

```bash
./gradlew bootJar -x test
```

### 2. Run Locally (against an existing O3 instance)

Use the local compose file which only starts the adaptor and connects to a running O3 instance on the `o3_default` Docker network:

```bash
docker compose -f docker-compose.local.yml up --build
```

The adaptor will be available at `http://localhost:8084`.

### 3. Run Full Stack (O3 + Adaptor)

Use the main compose file to spin up a complete O3 reference application alongside the adaptor:

```bash
docker compose up --build
```

| Service | URL |
|---------|-----|
| OpenMRS O3 UI | http://localhost:9096/openmrs |
| CCE Receiver Adaptor | http://localhost:8084 |

Default OpenMRS credentials: `admin` / `Admin123`

### 4. Run without Docker

```bash
./gradlew bootRun
```

The adaptor starts on port `8080` by default and expects OpenMRS at `http://localhost:8080/openmrs`.

## Configuration

All configuration is via environment variables (see `application.yml`):

| Variable | Default | Description |
|----------|---------|-------------|
| `OPENMRS_REST_BASE_URL` | `http://localhost:8080/openmrs/ws/rest/v1` | OpenMRS REST v1 base URL |
| `OPENMRS_FHIR_BASE_URL` | `http://localhost:8080/openmrs/ws/fhir2/R4` | OpenMRS FHIR2 R4 base URL |
| `OPENMRS_AUTH_TYPE` | `basic` | Auth type: `basic` or `oauth2` |
| `OPENMRS_USERNAME` | `admin` | Basic auth username |
| `OPENMRS_PASSWORD` | `Admin123` | Basic auth password |
| `OPENMRS_IDENTIFIER_TYPE_NAME` | `OpenMRS ID` | Primary patient identifier type |
| `CCE_SECURITY_ENABLED` | `false` | Enable inbound API auth |
| `LOG_LEVEL` | `INFO` | Application log level |

Auto-discovery at startup populates location UUID, identifier types, visit types, encounter types, and idgen source from the connected OpenMRS instance.

## API Usage

### Send a FHIR Bundle

```bash
curl -X POST http://localhost:8084/api/v1/openmrs/fhir \
  -H 'Content-Type: application/fhir+json' \
  -H 'X-Correlation-ID: test-001' \
  -H 'X-Source-System: SPICE' \
  -d @bundle.json
```

### Response

```json
{
  "bundleId": "test-001",
  "totalResources": 1,
  "successCount": 1,
  "failureCount": 0,
  "processedAt": "2026-04-29T09:30:56Z",
  "results": [
    {
      "resourceType": "ServiceRequest",
      "resourceId": "0fe4c80e-...",
      "route": "REST:/order",
      "status": "created",
      "httpStatus": 201
    }
  ]
}
```

HTTP status codes: `202 Accepted` (all succeed), `207 Multi-Status` (partial), `422 Unprocessable Entity` (all fail).

### Health Check

```bash
curl http://localhost:8084/actuator/health
```

## Documentation

Detailed documentation is available in the [`docs/`](docs/) directory:

- [Architecture](docs/architecture.md)
- [API Reference](docs/api-reference.md)
- [Configuration Guide](docs/configuration-guide.md)
- [Deployment Guide](docs/deployment-guide.md)
- [Operations Runbook](docs/operations-runbook.md)
- [SPICE-OpenMRS Referral Use Case](docs/spice-openmrs-referral-use-case.md)
- [Referral API Walkthrough](docs/referral-use-case-api-walkthrough.md)
- [Beckn Integration](docs/beckn-integration.md) (Spice referrals)
- [Sygnal Integration](docs/sygnal-integration.md) (CCE care alerts)

### Beckn

Built for the demo, and on by default on the `demo-beckn` branch (`OPENMRS_RECEIVER_BECKN_ENABLED=true`; set `false` to switch it off): the adaptor gets two Beckn endpoints, `POST /beckn/bpp-webhook/{action}` (referrals from Spice, via `openmrs-onix`'s `onix-bpp`: `update` with FHIR resources, or the health-referral flow `select` / `init` / `confirm` / `status`) and `POST /beckn/bap-webhook/{action}` (callbacks, logged only). They sit outside `/api/**` so the bearer-token filter does not reject the Beckn signature header. They answer in the same `Ack` / `Nack` body as `cce-beckn-service`. The existing `/api/v1/openmrs/fhir` endpoint is unchanged. Since 2026-11-23 the `performanceAttributes` box of the referral messages (`update`, `on_update`, `on_status`) is a `sygnal:CoordinationEvent` with an `@context` (schema `CoordinationEvent/v3.0` of `cce-beckn-service`), in place of the `cce:CceClinicalEvent`; the adaptor reads only its `resource`, so it handles them as before. See [docs/beckn-integration.md](docs/beckn-integration.md).

### Sygnal (CCE care alerts)

CCE (`cce-beckn-service`) sends its care alerts as Sygnal `sygnal/signal/emit`, through `onix-openmrs`, to `POST /sygnal/signal/emit` (same switch, `OPENMRS_RECEIVER_BECKN_ENABLED`). The `message` is a `sygnal:Signal` with the care-alert fields directly on it (CCE's Sygnal profile v3.0, since 2026-11-23; a `cce:CceCareAlert` message and a Signal with only a `signalAttributes` bag are refused). Since 2026-11-24 CCE's own level (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`) is only in `cceSeverity` on the Signal, and CCE does not send the Signal's `severity`; the adaptor takes the notification severity only from `cceSeverity`. The adaptor checks the sender (`OPENMRS_RECEIVER_BECKN_CCE_ID`, default `bpp.cce.nextsteps.mdtlabs.org`) and receiver (`OPENMRS_RECEIVER_BECKN_SELF_ID`, default `bap.openmrs.nextsteps.mdtlabs.org`), answers `Ack` / `Nack` at once and raises an OpenMRS notification afterwards. CCE alerts no longer come as a Beckn `on_status`; the Spice referral flows above are unchanged. See [docs/sygnal-integration.md](docs/sygnal-integration.md).

## Project Structure

```
src/main/java/org/openphc/cce/receiver/
├── CceReceiverAdaptorApplication.java
├── config/
│   ├── OpenMrsProperties.java         # YAML-bound configuration
│   ├── DiscoveredConfig.java          # Runtime auto-discovered config
│   ├── OpenMrsConfigDiscovery.java    # Startup discovery from OpenMRS
│   ├── RestClientConfig.java          # REST + FHIR RestClient beans
│   ├── SecurityConfig.java            # Inbound API auth
│   ├── OAuth2TokenProvider.java       # Outbound OAuth2 token cache
│   ├── RetryConfig.java               # Spring Retry configuration
│   └── FhirConfig.java               # HAPI FHIR context (inbound parsing)
├── controller/
│   ├── InboundResourceController.java # POST /api/v1/openmrs/fhir
│   ├── BecknWebhookController.java    # POST /beckn/{bpp,bap}-webhook/{action} (Spice referrals)
│   └── SygnalController.java          # POST /sygnal/signal/emit (CCE care alerts)
├── service/
│   ├── InboundProcessingService.java  # Orchestrator
│   ├── BundleSplitter.java            # Bundle decomposition
│   ├── ResourceRouter.java            # REST-first routing
│   ├── OpenMrsRestClient.java         # Primary REST client
│   ├── OpenMrsFhirClient.java         # FHIR fallback client
│   ├── FhirToRestTransformer.java     # FHIR → REST payload conversion
│   ├── ReferenceResolver.java         # Reference → UUID resolution
│   ├── ConceptResolver.java           # Code → concept UUID resolution
│   ├── PatientIdentifierEnricher.java # OpenMRS ID generation
│   ├── RequiredFieldEnricher.java     # Auto-fill missing required fields
│   ├── VisitManager.java             # Visit lifecycle management
│   └── SourceIdMappingStore.java      # Source-ID → OpenMRS UUID mapping
├── fhir/
│   └── FhirResourceParser.java        # HAPI FHIR parse/encode wrapper
├── model/
│   ├── ResourceEntry.java
│   ├── RoutingResult.java
│   └── ProcessingResponse.java
└── exception/
    ├── FhirParsingException.java
    ├── ResourceTransformException.java
    ├── OpenMrsClientException.java
    └── GlobalExceptionHandler.java
```

## License

See [LICENSE](LICENSE) for details.
