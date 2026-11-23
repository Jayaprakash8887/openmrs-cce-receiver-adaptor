# Deployment Guide — OpenMRS CCE Receiver Adaptor

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21 LTS |
| Gradle | 8.x (wrapper included) |
| Docker | 24.x+ (for containerized deployment) |
| OpenMRS | O3 with `fhir2` and `webservices.rest` modules |
| OpenMRS Modules | `idgen` (for identifier generation discovery) |

---

## Build

```bash
# Build with tests
./gradlew clean build

# Build without tests
./gradlew clean build -x test

# Build Docker image
docker build -t openmrs-cce-receiver-adaptor:latest .
```

---

## Run Locally

```bash
# Using Gradle
./gradlew bootRun --args='--spring.profiles.active=local'

# Using JAR directly
java -jar build/libs/cce-receiver-adaptor-1.0.0-SNAPSHOT.jar \
  --spring.profiles.active=local

# Verify health
curl -s http://localhost:8080/actuator/health | jq

# Test with a sample FHIR Patient
curl -X POST http://localhost:8080/api/v1/openmrs/fhir \
  -H "Content-Type: application/fhir+json" \
  -d '{
    "resourceType": "Patient",
    "name": [{"family": "Test", "given": ["User"]}],
    "gender": "male",
    "birthDate": "1990-01-01"
  }'
```

---

## Docker Compose

```yaml
services:
  openmrs-cce-receiver-adaptor:
    build: .
    image: openmrs-cce-receiver-adaptor:latest
    container_name: openmrs-cce-receiver-adaptor
    ports:
      - "8080:8080"
    restart: unless-stopped
    environment:
      SERVER_PORT: 8080
      JAVA_TOOL_OPTIONS: "-Xms256m -Xmx512m -XX:+UseG1GC"

      # OpenMRS connection
      OPENMRS_REST_BASE_URL: http://openmrs:8080/openmrs/ws/rest/v1
      OPENMRS_FHIR_BASE_URL: http://openmrs:8080/openmrs/ws/fhir2/R4
      OPENMRS_AUTH_TYPE: basic
      OPENMRS_AUTH_USERNAME: admin
      OPENMRS_AUTH_PASSWORD: Admin123

      # Inbound security
      CCE_SECURITY_ENABLED: "true"
      CCE_SECURITY_USERNAME: cce-client
      CCE_SECURITY_PASSWORD: strong-secret

      # Identifier config (auto-discovered if blank)
      OPENMRS_IDENTIFIER_TYPE_NAME: OpenMRS ID
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health/liveness"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s
    depends_on:
      openmrs:
        condition: service_healthy
```

---

## Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

---

## Resource Allocation

| Resource | Minimum | Recommended | High Throughput |
|----------|---------|-------------|-----------------|
| CPU | 0.5 vCPU | 1 vCPU | 2 vCPU |
| Memory | 256 MB | 512 MB | 1 GB |
| Disk | 10 MB | 50 MB | 50 MB |

> **Note:** The adaptor is stateless (no persistent disk needed). Memory is the primary resource — larger Bundles with many resources require more heap. The default `-Xmx512m` handles Bundles with up to ~100 entries comfortably.

---

## Network Requirements

| Source | Destination | Port | Protocol | Purpose |
|--------|-------------|------|----------|---------|
| CCE Core (Intelligence Service) | Receiver Adaptor | 8080 | HTTP/HTTPS | Inbound FHIR payloads |
| Receiver Adaptor | OpenMRS | 8080/9096 | HTTP | REST + FHIR API calls |
| Receiver Adaptor | Keycloak (optional) | 8180 | HTTP/HTTPS | OAuth2 token acquisition |
| Prometheus | Receiver Adaptor | 8080 | HTTP | Metrics scraping |

---

## Beckn

> **Status:** Built (demo), off by default. Full design: [beckn-integration.md](beckn-integration.md).

The Beckn endpoints are optional. Set `OPENMRS_RECEIVER_BECKN_ENABLED=true` to turn them on; leave it unset and nothing changes. The existing `/api/v1/openmrs/fhir` endpoint is unchanged either way.

**Extra containers.** `openmrs-onix` runs `onix-bap`, `onix-bpp` and their Redis. They go in the same compose project as an optional group, on the `cce-net` network with the adaptor.

**Environment.**

```yaml
  openmrs-cce-receiver-adaptor:
    environment:
      OPENMRS_RECEIVER_BECKN_ENABLED: "true"
      OPENMRS_RECEIVER_BECKN_ONIX_BPP_CALLER_URL: http://onix-bpp:8082/bpp/caller
      OPENMRS_RECEIVER_BECKN_SELF_BPP_ID: bpp.openmrs.example.org
      OPENMRS_RECEIVER_BECKN_SELF_BPP_URI: https://openmrs.example.org/bpp/receiver
      OPENMRS_RECEIVER_BECKN_SPICE_BAP_ID: bap.spice.example.org
      OPENMRS_RECEIVER_BECKN_SPICE_BAP_URI: https://spice.example.org/bap/receiver
```

The ids above are made up. With `OPENMRS_RECEIVER_BECKN_ENABLED=true` the adaptor does not start while any of them is blank. All settings are in the [Configuration Guide](configuration-guide.md#beckn).

**onix routing.** `openmrs-onix` is a copy of `cce-onix` today, and its receiver routing still points at `host.docker.internal:8075`. Change `target.url` in `devkit/config/`:

| File | `target.url` |
|---|---|
| `local-simple-routing-BPPReceiver.yaml` | `http://openmrs-cce-receiver-adaptor:8080/beckn/bpp-webhook` |
| `local-simple-routing-BAPReceiver.yaml` | `http://openmrs-cce-receiver-adaptor:8080/beckn/bap-webhook` |

**onix-bpp wait.** The adaptor answers as soon as the write path returns, but the write path can be slow (several OpenMRS calls, each up to `OPENMRS_REST_TIMEOUT` / `OPENMRS_FHIR_TIMEOUT`). Set `onix-bpp`'s `responseHeaderTimeout` longer than the write path's worst case. onix's default of 5 s is too short. `spice-onix` must then wait longer than `onix-bpp`; agree the numbers with the Spice team.

**Schema.** Since 2026-11-23 the schema set of `onix-bap` and `onix-bpp` (onix 1.9.4) is `CoordinationEvent/v3.0` plus the Health* pack, in place of `CceClinicalEvent/v1.0` (deleted). The `CoordinationEvent/v3.0` files (`attributes.yaml`, `context.jsonld`) come from `cce-beckn-service`'s `schema/` folder. The default schema base URL is `https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema`. onix finds the box's schema by its `@type`: `sygnal:CoordinationEvent` → `CoordinationEvent/attributes.yaml` in its local schema folder. Spice's referral adapters change at the same time; an adapter without the old schema refuses the old `cce:CceClinicalEvent` box (`SCH_SCHEMA_ADAPTATION_FAILED`).

**Network and ports.**

| Source | Destination | Port | Purpose |
|---|---|---|---|
| `onix-bpp` | Receiver Adaptor | 8080 | Referrals from Spice (`/beckn/bpp-webhook`) |
| `onix-bap` | Receiver Adaptor | 8080 | Callbacks (`/beckn/bap-webhook`) |
| Receiver Adaptor | `onix-bpp` | 8082 | `on_update` to Spice (`/bpp/caller/on_update`) |

On the dev host the adaptor is published on host port 8097 (container 8080). Host ports 8081 (`keycloak`) and 8082 (`cce-openhim-core`) are taken, so publish `onix-bap` and `onix-bpp` on 8181 and 8182. Inside `cce-net` they keep 8081 and 8082. Host port 8075 belongs to `care-coordinator`.

Do not expose `/beckn/**` through a public proxy. Only `openmrs-onix` should reach it.

**Checklist.**

- [ ] `OPENMRS_RECEIVER_BECKN_ENABLED=true` on the adaptor
- [ ] Both onix receiver routings point at `openmrs-cce-receiver-adaptor:8080/beckn/...`
- [ ] Adaptor and onix containers on `cce-net`
- [ ] `onix-bpp`'s `responseHeaderTimeout` raised above the write path's worst case (not the 5 s default)
- [ ] onix uses the `cce-beckn-service` schema base URL
- [ ] `onix-bap` and `onix-bpp` hold `CoordinationEvent/v3.0` and the Health* pack, and Spice's adapters hold the same set
- [ ] "Referrals in" contract id and `transactionId` match the emitter's and Spice's settings

---

## Sygnal (CCE care alerts)

> **Status:** Built (demo), on by default on this branch. Full design: [sygnal-integration.md](sygnal-integration.md).

CCE sends care alerts as Sygnal `sygnal/signal/emit`. `onix-openmrs` receives them on its `/sygnal/receiver/` module, checks the signature and the schema, and forwards them to `http://openmrs-cce-receiver-adaptor:8080`; onix appends the action, so the adaptor gets `POST /sygnal/signal/emit`. The Spice Beckn routing above is unchanged. `onix-openmrs` needs the CCE Sygnal profile v3.0 as two files in one folder: `cce-sygnal-apis.yaml`, loaded as its spec, and `cce-fhir-extension.yaml`, which the first refers to (`schema/sygnal-profile/v3.0/` in `cce-beckn-service`).

**Environment.** The defaults are the demo network's ids; set them only when they differ:

```yaml
  openmrs-cce-receiver-adaptor:
    environment:
      OPENMRS_RECEIVER_BECKN_SELF_ID: bap.openmrs.nextsteps.mdtlabs.org
      OPENMRS_RECEIVER_BECKN_CCE_ID: bpp.cce.nextsteps.mdtlabs.org
```

| Source | Destination | Port | Purpose |
|---|---|---|---|
| `onix-openmrs` (`/sygnal/receiver/`) | Receiver Adaptor | 8080 | Care alerts (`/sygnal/signal/emit`) |

The adaptor answers before it raises the notification, so onix's default wait is enough. Do not expose `/sygnal/**` of the adaptor through a public proxy; the public entry is onix's `/sygnal/receiver/`. `cce-beckn-service` must have OpenMRS's public onix receiver base (`https://<host>/sygnal/receiver`) as its `receiverUri`.

---

## Startup Sequence

The adaptor performs these steps on startup:

1. **Spring Boot context initialization** — beans, security, REST clients
2. **OpenMRS config discovery** (`@EventListener(ApplicationReadyEvent)`) — queries OpenMRS REST API to discover:
   - Location UUID (Login Location)
   - Identifier types (OpenMRS ID + source types)
   - Idgen source UUID
   - Visit type UUID
   - Encounter types (full map)
   - Person attribute types
3. **Ready to accept requests** — `/actuator/health/readiness` returns `UP`

> **Important:** If OpenMRS is unavailable at startup, discovery will fail with warnings. The adaptor will still start but may fail to process requests until OpenMRS becomes available and discovery succeeds on the next capability refresh cycle.

---

## Deployment Checklist

### Pre-deployment

- [ ] OpenMRS instance is reachable from the adaptor
- [ ] OpenMRS user has sufficient privileges (Patient, Encounter, Obs, Order, Visit, Provider, Location CRUD)
- [ ] OpenMRS modules installed: `fhir2`, `webservices.rest`, `idgen`
- [ ] OpenMRS concept dictionary has required source mappings (LOINC, SNOMED, CIEL, ICD-11, etc.)
- [ ] At least one Login Location exists in OpenMRS
- [ ] At least one Patient Identifier Type ("OpenMRS ID") exists

### Configuration

- [ ] `OPENMRS_REST_BASE_URL` and `OPENMRS_FHIR_BASE_URL` set correctly
- [ ] OpenMRS credentials configured (not hardcoded)
- [ ] Inbound security enabled for production (`CCE_SECURITY_ENABLED=true`)
- [ ] Inbound credentials are strong and injected via secrets
- [ ] `OPENMRS_NOTIFICATION_RECIPIENTS_STATIC_UUIDS` overridden with real OpenMRS **user** UUID(s) for the target environment (the baked-in default is a local-dev admin UUID and will not exist in EC2/prod)

### Verification

- [ ] Health check responds: `GET /actuator/health`
- [ ] Test FHIR Patient payload processes successfully
- [ ] Prometheus scraping configured for `/actuator/prometheus`

---

## Scaling Considerations

| Scenario | Recommendation |
|----------|---------------|
| Single facility | 1 instance, 512 MB |
| Multiple Intelligence Service instances | 1 adaptor instance per OpenMRS instance |
| High-throughput (>100 Bundles/min) | Increase memory to 1 GB; consider horizontal scaling behind load balancer |
| Multi-OpenMRS | Deploy separate adaptor per OpenMRS instance (each with own config) |

> **Stateless design:** The adaptor has no persistent state. All concept and reference lookups are resolved live from OpenMRS on every request. Multiple instances can run behind a load balancer with no coordination needed. For idempotent updates, source systems should include OpenMRS UUIDs from prior responses.

---

## Upgrading

```bash
# Build new image
./gradlew clean build
docker build -t openmrs-cce-receiver-adaptor:latest .

# Rolling restart
docker compose up -d --no-deps openmrs-cce-receiver-adaptor

# Verify
curl -s http://localhost:8080/actuator/health | jq
```

No database migrations or state cleanup needed — the adaptor is stateless.
