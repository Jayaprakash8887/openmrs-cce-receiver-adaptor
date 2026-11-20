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

## Sygnal (CCE care alerts)

> **Status:** Built (demo), on by default on this branch. Full design: [sygnal-integration.md](sygnal-integration.md).

CCE sends care alerts to OpenMRS as Sygnal `sygnal/signal/emit` messages. `onix-openmrs` receives them, checks the signature and the schema, and forwards them to the adaptor at `POST /sygnal/signal/emit`. Set `OPENMRS_RECEIVER_BECKN_ENABLED=false` to switch the endpoint off. The existing `/api/v1/openmrs/fhir` endpoint is unchanged either way.

**Extra container.** `onix-openmrs` (one onix with the `/sygnal/receiver/` and `/sygnal/caller/` modules, port 8082) and its Redis, on the `cce-net` network with the adaptor.

**Environment.** The defaults are the demo network's ids; set them only when they differ:

```yaml
  openmrs-cce-receiver-adaptor:
    environment:
      OPENMRS_RECEIVER_BECKN_ENABLED: "true"
      OPENMRS_RECEIVER_BECKN_SELF_ID: bap.openmrs.nextsteps.mdtlabs.org
      OPENMRS_RECEIVER_BECKN_CCE_ID: bpp.cce.nextsteps.mdtlabs.org
```

All settings are in the [Configuration Guide](configuration-guide.md#sygnal-cce-care-alerts). Remove the old `OPENMRS_RECEIVER_BECKN_ONIX_*`, `*_SELF_BPP_*`, `*_SPICE_*`, `*_NETWORK_ID`, `*_REFERRALS_IN_*` and `*_HEALTH_ID_TYPES` variables; they are no longer read.

**onix routing.** In `onix-openmrs`'s `/sygnal/receiver/` routing, the action `sygnal/signal/emit` goes to `http://openmrs-cce-receiver-adaptor:8080`. onix appends the action, so the adaptor receives `POST /sygnal/signal/emit`. The old `bpp-webhook` / `bap-webhook` routes are gone.

**Wait.** The adaptor answers before it raises the notification, so onix's default wait is enough.

**Schema.** onix checks `signalAttributes` against the `CceCareAlert` schema from `cce-beckn-service`'s `schema/` folder. The default schema base URL is `https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema`.

**Network and ports.**

| Source | Destination | Port | Purpose |
|---|---|---|---|
| `onix-openmrs` | Receiver Adaptor | 8080 | Care alerts (`/sygnal/signal/emit`) |

The adaptor sends nothing to onix. On the dev host the adaptor is published on host port 8097 (container 8080). Host port 8082 is taken by `cce-openhim-core`, so publish `onix-openmrs` on another host port; inside `cce-net` it keeps 8082. Host port 8075 belongs to `care-coordinator`.

Do not expose `/sygnal/**` of the adaptor through a public proxy. Only `onix-openmrs` should reach it; the public entry is onix's `/sygnal/receiver/`.

**Checklist.**

- [ ] `OPENMRS_RECEIVER_BECKN_ENABLED=true` on the adaptor (the default)
- [ ] `OPENMRS_RECEIVER_BECKN_SELF_ID` / `OPENMRS_RECEIVER_BECKN_CCE_ID` match the registry and `cce-beckn-service`
- [ ] `onix-openmrs` routes `sygnal/signal/emit` to `http://openmrs-cce-receiver-adaptor:8080`
- [ ] Adaptor and `onix-openmrs` on `cce-net`
- [ ] onix uses the `cce-beckn-service` schema base URL
- [ ] `cce-beckn-service` has OpenMRS's public onix receiver base (`https://<host>/sygnal/receiver`) as its `receiverUri`

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
