# ☸️ SkyBook Kubernetes — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Kubernetes manifests for the full platform: Deployments, ClusterIP Services, Ingress, ConfigMaps, Secrets, health probes, resource limits — only the API Gateway publicly exposed |
| **Branch** | `feature/kubernetes` |
| **Status** | Frozen after design review (seven corrections applied: Alloy over EOL'd Promtail, explicit readiness groups, OCI-image agent delivery, complete Secret inventory, hostless Ingress, corrected exposure audit, PVC init semantics). Implementation starting per §13. |

Goal: the platform that runs under `docker compose up` runs identically on a Kubernetes cluster, deployed from declarative manifests, consuming the GHCR images CI already publishes — and, critically, **the trust-boundary assumption every prior design leaned on becomes real**: `API_GATEWAY_MODULE.md` §8 explicitly deferred "only the gateway is reachable from outside" to this branch. In compose, every service publishes its port to the host; here, only the gateway gets an Ingress route.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [Architecture](#3-architecture)
4. [Manifest Strategy: Kustomize, No Helm](#4-manifest-strategy-kustomize-no-helm)
5. [Workloads](#5-workloads)
6. [Configuration & Secrets](#6-configuration--secrets)
7. [Health Probes](#7-health-probes)
8. [Resource Limits](#8-resource-limits)
9. [Ingress & the Trust Boundary](#9-ingress--the-trust-boundary)
10. [Observability on the Cluster](#10-observability-on-the-cluster)
11. [Deferred / Out of Scope](#11-deferred--out-of-scope)
12. [Known Risks / Open Questions](#12-known-risks--open-questions)
13. [Build Order](#13-build-order)
14. [Testing / Verification Plan](#14-testing--verification-plan)

---

# 1. Overview

Everything lands under `k8s/` at the repo root, applied with `kubectl apply -k k8s/` into a dedicated `skybook` namespace:

- **8 app Deployments** (gateway + 7 services) pulling `ghcr.io/praveencloudlab/skybook-<service>` — the images CI already builds and pushes on every `main` merge; no local image building involved in deployment at all.
- **Postgres and Kafka as single-replica StatefulSets** with PersistentVolumeClaims — same single-node dev-grade topology as compose, expressed in the idiomatic k8s form.
- **The observability quintet** (Prometheus, Grafana, Loki, Tempo, **Grafana Alloy**) — Deployments + PVCs, except Alloy which runs as a **DaemonSet** (the k8s-native form of "tail every container's logs on every node"). Alloy, *not* Promtail: Promtail reached end of life on **2026-03-02** — already past — and Grafana's own guidance directs new deployments to Alloy (design-review catch; the compose stack keeps Promtail temporarily, with its Alloy migration recorded as follow-up work in §11).
- **ClusterIP Services everywhere; one Ingress** routing to the gateway. Nothing else is reachable from outside the cluster.
- **ConfigMaps** for topology config (service DNS names replace compose service names), **Secrets** for seven scalar values (§6) — the five `.env` holds today plus the Postgres credentials compose currently hardcodes.

**Target cluster: Docker Desktop's built-in Kubernetes** — kubectl v1.34.1 and kustomize are already on the machine (confirmed); no cluster exists yet (no contexts, no minikube/kind/helm). Enabling it is one checkbox (Docker Desktop → Settings → Kubernetes → Enable) — the user's single external prerequisite for this branch, same category as the SonarCloud token was for CI.

---

# 2. Load-Bearing Findings

Confirmed, not assumed:

1. **No cluster or cluster tooling exists yet** — `kubectl config get-contexts` is empty; no helm/minikube/kind installed. kubectl v1.34.1 with built-in kustomize v5.7.1 is present. This drives two decisions: Docker Desktop K8s as the target (zero new installs beyond the checkbox — the Docker Desktop it ships in is already the project's runtime), and Kustomize over Helm (§4 — the tool is already in hand, and there is exactly one environment to render).
2. **The GHCR images are public and complete.** CI pushes all 8 services with `latest` + commit-SHA tags on every `main` merge, verified pullable anonymously (done live during the CI/CD branch). The cluster needs no `imagePullSecrets`, no registry auth, no local builds — deployment consumes exactly the artifact the pipeline produces, the handoff the CI/CD design doc (§8) planned for.
3. **Spring Boot auto-enables the probe *endpoints* in-cluster — but their default contents are NOT what the first draft assumed.** When Boot detects k8s (via `KUBERNETES_SERVICE_HOST`), `/actuator/health/liveness` and `/actuator/health/readiness` activate automatically — but by default **readiness contains only `readinessState`, not the DB indicator**: killing Postgres would *not* have made pods NotReady, silently voiding the probe behavior §7 and §14 promise (design-review catch). The fix is an explicit, deliberate per-service health-group config (§7): DB-backed services include `db` in readiness; the gateway and notification-service (no DB) don't; **Kafka deliberately stays out of readiness everywhere** — with the resilience branch's DLTs and consumer retries, a temporarily-unavailable broker is degraded async processing, not a reason to pull a service out of HTTP rotation. Liveness includes external dependencies nowhere (Boot's own warning: shared-dependency outages become restart storms otherwise).
4. **Every piece of environment-specific config already flows through env vars** — the dockerization branch's whole design (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `*_BASE_URL`, `OTEL_*` via relaxed binding). Kubernetes ConfigMaps/Secrets feed the same variables with cluster-DNS values (`http://flight-service:8082` stays literally identical — compose service names and k8s Service names are both DNS). **Zero application *code* changes in this branch**; the one config-file addition is the explicit health-group block per service that finding §2.3's correction requires (additive `application.yml` lines, inert outside k8s probes).
5. **The compose file is the authoritative topology inventory** — every env var, dependency edge, healthcheck, and volume transcribes mechanically, **except the secret inventory, which compose understates** (design-review catch): `.env` holds five scalars (`JWT_SECRET`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `CHECKIN_BOARDING_PASS_KEY`, `GRAFANA_ADMIN_PASSWORD`), not four as first drafted, and compose *hardcodes* `postgres`/`postgres` credentials that k8s must treat as Secret material — seven values total (§6). The other non-mechanical translations: the `otel-agent` delivery (compose one-shot download container → **pinned OCI image + initContainer copy**, §5 — no GitHub download at pod startup, design-review catch), log collection (Promtail Docker-socket discovery → **Alloy DaemonSet** with node log paths, §10), and Kafka's advertised listeners (compose hostname → headless-Service DNS, §5).
6. **Docker Desktop K8s ships no Ingress controller.** `ingress-nginx` is installed once from its official published manifest (version pinned in our repo as `k8s/ingress-nginx/` copy, not a live URL fetch at apply time) — Docker Desktop supports `LoadBalancer`-type services on localhost, which is how ingress-nginx's own front door binds to `http://localhost`.

---

# 3. Architecture

```
                       host (browser / Postman)
                              │  http://localhost  (only entry point)
                              ▼
                    ingress-nginx (LoadBalancer)
                              │  Ingress: hostless rule, / → api-gateway:8080
                              │  (no host field - http://localhost just works,
                              │   no Windows hosts-file edits; design review)
   ┌──────────────────────────┼──────────────────────── namespace: skybook ──┐
   │                          ▼                                               │
   │                 api-gateway (ClusterIP)                                  │
   │        ┌───────────┬─────┴──────┬────────────┬───────────┐              │
   │        ▼           ▼            ▼            ▼           ▼              │
   │   auth-service flight-svc booking-svc inventory-svc payment-svc         │
   │   checkin-svc  notification-svc          (all ClusterIP, no Ingress)    │
   │        │           │            │            │           │              │
   │        ▼           ▼            ▼            ▼           ▼              │
   │   postgres (StatefulSet+PVC)         kafka (StatefulSet+PVC, KRaft)     │
   │                                                                          │
   │   prometheus • grafana • loki • tempo (Deployments+PVCs)                │
   │   grafana alloy (DaemonSet, node log paths → Loki)                      │
   └──────────────────────────────────────────────────────────────────────────┘
```

The compose stack remains fully supported for day-to-day dev — this branch adds a deployment target, it does not replace `docker compose up`.

# 4. Manifest Strategy: Kustomize, No Helm

Plain YAML manifests under `k8s/base/`, tied together by a `kustomization.yaml`, applied with the kubectl that's already installed (`kubectl apply -k`). **Not Helm**: Helm isn't installed, templating buys nothing with a single environment and zero chart consumers, and every value a chart would parameterize is already a ConfigMap/Secret. Kustomize's patch/overlay model is there for free the day a second environment (a real cloud cluster) appears — `k8s/overlays/<env>/` is the natural extension point, deliberately not created until it has a real member.

One manifest file per component (`k8s/base/booking-service.yaml` holding its Deployment + Service together), mirroring how the compose file groups per-service concerns — greppable, reviewable, no cross-file hunting for a service's full definition.

# 5. Workloads

| Component | Kind | Notes |
|---|---|---|
| 8 app services | Deployment (replicas: 1) + ClusterIP Service | Image `ghcr.io/praveencloudlab/skybook-<name>:latest` for dev (SHA-pinning noted in §12); OTel agent via **initContainer copying from a pinned project-owned OCI image** (`ghcr.io/praveencloudlab/skybook-otel-agent:<version>` → `emptyDir` mounted at `/otel`). *Not* a startup-time GitHub download (first-draft idea, design-review catch): that would make every pod start depend on external internet, GitHub availability, DNS, TLS, and rate limits — undoing GHCR's reproducibility. The agent image is built once per agent-version bump (checksum verified at *image build* time, where a failure is a build error, not an outage) |
| postgres | StatefulSet (replicas: 1) + headless Service + PVC | `postgres:16-alpine`; init SQL (six databases) via ConfigMap mounted at `/docker-entrypoint-initdb.d` — same file as `docker/postgres/` |
| kafka | StatefulSet (replicas: 1) + headless Service + PVC | `apache/kafka:3.9.0`, same KRaft env block as compose including the replication-factor-1 settings (the consumer-offsets lesson carries over verbatim); `KAFKA_ADVERTISED_LISTENERS` becomes the pod's stable StatefulSet DNS name (`kafka-0.kafka.skybook.svc.cluster.local:9092`) — the one env value that is *not* a mechanical copy |
| prometheus, grafana, loki, tempo | Deployment (replicas: 1) + ClusterIP Service + PVC each | Configs via ConfigMaps (same files as `docker/<component>/`); Grafana provisioning ConfigMaps mirror `docker/grafana/` |
| grafana alloy | DaemonSet + RBAC (ServiceAccount, ClusterRole for pod discovery) | Replaces Promtail (EOL 2026-03-02, design-review catch — a new deployment must not start on an unsupported shipper). Same pipeline shape: discover k8s pods → read `/var/log/pods` (hostPath) → parse JSON → push to Loki; namespace/pod/container labels, Grafana queries, and log↔trace navigation all unchanged |

Replicas stay at 1 everywhere — same single-instance semantics as compose (and the gateway's in-memory rate limiter plus the Kafka partition counts all assume it; scaling out is a documented non-goal, §11).

# 6. Configuration & Secrets

- **One ConfigMap per app service** carrying its non-secret env block, transcribed from compose: datasource URLs (`jdbc:postgresql://postgres:5432/skybook_x` — database *names* stay ConfigMap material), `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`, `*_BASE_URL` values (service DNS — literally identical strings to compose), `OTEL_*` block, `SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT`.
- **One `skybook-secrets` Secret with seven scalar values** (design-review correction — the first draft said "four" while listing five, and missed the Postgres credentials entirely because compose hardcodes them):

  | Key | Consumed by |
  |---|---|
  | `POSTGRES_USER`, `POSTGRES_PASSWORD` | postgres StatefulSet (as its own env) **and** every DB-backed Deployment (as `SPRING_DATASOURCE_USERNAME`/`SPRING_DATASOURCE_PASSWORD` — the values compose let the services hardcode) |
  | `JWT_SECRET` | auth-service, api-gateway |
  | `MAIL_USERNAME`, `MAIL_PASSWORD` | notification-service |
  | `CHECKIN_BOARDING_PASS_KEY` | checkin-service |
  | `GRAFANA_ADMIN_PASSWORD` | grafana |

- Created from a gitignored file (`k8s/secrets.env`, template `k8s/secrets.env.example`) via Kustomize's `secretGenerator`, so `kubectl apply -k` stays the single command. **`k8s/secrets.env` gets its own explicit `.gitignore` entry** — the existing `.env`/`.env.*` patterns do *not* match a file named `secrets.env` (design-review catch; verified against the actual gitignore rules rather than assumed). Plain base64 Secrets, honestly labeled: real secret *management* (sealed-secrets/Vault/SOPS) is `feature/security-hardening` territory (§11).
- No Spring profiles, no second `application.yml` anywhere — findings §2.4's env-var discipline carries the whole weight (plus the one additive health-group block, §7).

# 7. Health Probes

Per app service, using the Boot probe endpoints — with the health-group membership made **explicit per service**, because Boot's defaults do not include external dependencies in readiness (finding §2.3, design-review catch: without this block, the "kill Postgres → NotReady" behavior §14 tests would silently not exist):

```yaml
# DB-backed services (auth, flight, booking, inventory, payment, checkin):
management:
  endpoint:
    health:
      group:
        liveness:
          include: livenessState          # never external deps - restart storms otherwise
        readiness:
          include: readinessState,db      # can't reach your DB -> out of rotation, not dead

# No-DB services (api-gateway, notification-service): readiness includes readinessState only.
```

- **Kafka is deliberately in *neither* group, anywhere.** With the resilience branch's consumer retries and DLTs, a temporarily-unreachable broker means degraded async processing — not a reason to pull a service's HTTP surface out of rotation, and certainly not a reason to restart it. Stated as a decision, not an omission.
- **readinessProbe** → `/actuator/health/readiness` — gates Service traffic; a DB-backed pod that can't reach Postgres stops receiving requests without being killed.
- **livenessProbe** → `/actuator/health/liveness` — restarts genuinely hung JVMs only.
- `initialDelaySeconds` ~40s / `failureThreshold` tuned to observed startup (JVM + OTel agent ≈ 15-25s in compose); a **startupProbe** absorbs cold-start variance so liveness can stay tight afterward.
- Infra containers keep their compose healthcheck logic as exec/httpGet probes (`pg_isready`, Kafka's `kafka-broker-api-versions.sh`, `/-/ready` etc.).

# 8. Resource Limits

Per app service, starting values — same "reasoned defaults, tuned via Grafana later" posture as every resilience threshold:

```yaml
resources:
  requests: { memory: "384Mi", cpu: "150m" }
  limits:   { memory: "768Mi" }   # no CPU limit - throttling JVMs hurts more than it protects, single-tenant cluster
```

- Compose showed the 8 JVMs + agent comfortably inside ~10GB total; 768Mi ceilings keep one leaking service from starving the node while leaving headroom above observed steady-state (~300-500Mi each).
- **No CPU limits** — CPU throttling on JVM startup/JIT causes probe-failure crash loops on single-tenant dev clusters; requests-only for scheduling is the standard posture and is stated explicitly rather than cargo-culted in.
- Postgres/Kafka/observability get their own modest requests; Grafana/Alloy are tiny.

# 9. Ingress & the Trust Boundary

- **One hostless Ingress rule** (design-review decision — the first draft's diagram said `skybook.local` while its tests said `http://localhost`, a contradiction):

```yaml
spec:
  ingressClassName: nginx
  rules:
    - http:                      # no host: field - matches any Host header,
        paths:                   # so http://localhost works with zero
          - path: /              # Windows hosts-file edits
            pathType: Prefix
            backend:
              service: { name: api-gateway, port: { number: 8080 } }
```

  `skybook.local` + a hosts-file entry was the alternative; rejected because it adds an external prerequisite for no gain on a single-app dev cluster.
- No TLS in this branch (localhost dev; TLS is meaningless theater without a real domain — `feature/security-hardening`/real-cloud concern).
- **No other Service is NodePort/LoadBalancer/externalIPs, no other Ingress rule exists.** This closes the loop `API_GATEWAY_MODULE.md` §8 opened: JWT enforcement at the gateway is now backed by actual network unreachability of the seven services, not by convention. Observability UIs (Grafana etc.) stay ClusterIP too — reached via `kubectl port-forward` (documented one-liners in the README section), keeping the "only the gateway is public" statement absolute rather than "except for four dashboards."
- **The exposure audit spans two namespaces** (design-review correction — the ingress controller's LoadBalancer lives in `ingress-nginx`, not `skybook`, so a single `-n skybook` query would miss it *and* prove nothing about the controller):

```
kubectl get svc -n skybook        # expect: every Service TYPE=ClusterIP, no exceptions
kubectl get ingress -n skybook    # expect: exactly one, routing only to api-gateway
kubectl get svc -A                # expect: the ONLY LoadBalancer in the cluster is
                                  # ingress-nginx's controller, in its own namespace
```

  Expected output written down in §14, including the explicit negative assertions (no NodePort, no LoadBalancer, no externalIPs on any skybook Service).

# 10. Observability on the Cluster

- Prometheus scrape config swaps static compose hostnames for **`kubernetes_sd_configs` with pod-annotation discovery** (`prometheus.io/scrape: "true"`, port/path annotations on each app pod) — the standard k8s pattern, and unlike a static list it survives pod restarts and future services automatically.
- **Alloy's DaemonSet** (Promtail's supported successor — §2/§5) discovers k8s pods, tails `/var/log/pods` (hostPath), parses the JSON log lines, and pushes to Loki with k8s metadata labels (namespace/pod/container) replacing compose's service label — Loki queries change shape slightly (`{namespace="skybook", pod=~"booking-service.*"}`); the fleet dashboard's Loki panel is updated accordingly. Loki storage, Grafana datasources, and log↔trace navigation are all untouched by the shipper swap.
- Tempo/Grafana/Loki configs are otherwise byte-identical ConfigMap mounts of the `docker/` files. The OTel env blocks on every pod are byte-identical too (`http://tempo:4317` resolves in-cluster exactly as in-compose).

# 11. Deferred / Out of Scope

- **Horizontal scaling / HPA / multiple replicas** — the gateway's in-memory rate limiter, single-partition topics, and single-node infra all assume 1 replica; scaling is real architectural work (Redis rate limiting, partitioned topics), not a `replicas: 3` edit. Documented, not attempted.
- **NetworkPolicies, PodSecurityStandards, non-root containers, TLS** — `feature/security-hardening`'s explicitly-planned scope; this branch establishes reachability boundaries only.
- **CD (auto-`kubectl apply` from CI)** — the CI/CD doc's §8 deferred deploy step; wire it once this branch proves the target works. Manual `kubectl apply -k` is the v1 deployment action.
- **Real secret management** (SOPS/sealed-secrets/Vault) — plain namespaced Secrets now, hardening branch later.
- **Cloud cluster / multi-node / storage classes beyond Docker Desktop's `hostpath`** — the kustomize overlay seam exists for when this matters.
- **Kafka/Postgres operators** — single-node StatefulSets are honest about what this is; operators earn their complexity at replication scale this project doesn't have.
- **Compose's Promtail → Alloy migration** — the compose stack still runs (already-EOL'd) Promtail; it keeps working today, but should follow this branch's Alloy precedent in a small follow-up so both environments converge on the supported shipper (design-review follow-up item).

# 12. Known Risks / Open Questions

- **`latest` tags in dev manifests** — convenient (always the newest merged image) but non-reproducible; `imagePullPolicy: Always` makes restarts pick up new images semi-implicitly. Accepted for the dev cluster with the SHA-tag alternative documented inline; a real environment overlay would pin SHAs (which CI already publishes).
- **Docker Desktop K8s shares the machine's Docker resources** — the full stack (8 JVMs + infra + observability) inside Docker Desktop's VM alongside the compose stack would double memory pressure; the README will say plainly: run one or the other, `docker compose down` before `kubectl apply`.
- **ingress-nginx on Docker Desktop occasionally fights Windows port 80 reservations** (HNS port exclusions) — flagged from research, verified empirically in build order step 2, with the port-8090-fallback documented if it bites.
- **All resource numbers are pre-load-test defaults** — same honesty clause as resilience thresholds; Grafana is the tuning feedback loop.
- **Alloy node-path assumptions** — `/var/log/pods` layout inside Docker Desktop's VM is the standard containerd layout, but verified empirically (build order step 6) rather than trusted, given this project's record of "the default isn't what the docs said."
- **Postgres init scripts run ONLY against an empty data directory** (design-review addition — `kubectl apply -k` must not be read as re-running them): `/docker-entrypoint-initdb.d` is a first-boot mechanism. Editing the database-init ConfigMap and re-applying does **nothing** to an existing PVC. Fresh PVC → the six databases are created; existing PVC → schema/database changes need migrations or manual SQL, or deliberately deleting the dev PVC to reinitialize. Documented in the README troubleshooting section so nobody debugs "my ConfigMap change didn't apply" as a Kubernetes problem.

# 13. Build Order

1. **User prerequisite: enable Docker Desktop Kubernetes** (Settings → Kubernetes → Enable; `kubectl get nodes` returns the node). Everything below is blocked on this single click.
2. **Health-group config in the 8 services** (§7's explicit readiness/liveness blocks — the one application-config change) + full reactor `mvn clean verify` + the OTel agent OCI image built and pushed once (`ghcr.io/praveencloudlab/skybook-otel-agent`, pinned tag) — the two pieces of *artifact* work before any manifest is written.
3. **Namespace + ingress-nginx** — pinned manifest copy applied; verify the controller's LoadBalancer binds localhost (and catch the port-80 risk early).
4. **postgres + kafka StatefulSets** — verify six databases created (fresh PVC — noting §12's init-only-on-empty semantics), KRaft broker healthy, `__consumer_offsets` creatable (the replication-factor lesson re-verified in the new home).
5. **flight-service first** (the dockerization template pattern: simplest DB-only service) — Deployment/Service/ConfigMap + probes + limits; verify Ready 1/1, and that readiness actually gates: kill Postgres → NotReady without restarts (now real behavior, per §7's explicit groups).
6. **Remaining 7 app services + the hostless Ingress** — full request path: register → login → JWT-protected call through `http://localhost` → correct 401/200 behavior; run the §9 two-namespace exposure audit.
7. **Observability stack** — Prometheus pod discovery finds 8 targets, Grafana provisioned dashboards render, **Alloy** ships pod logs, one OTel trace spans gateway→flight in Tempo (the §14 checks from the observability doc, re-run in-cluster).
8. **README deployment section** (`kubectl apply -k`, port-forward one-liners, compose-vs-k8s memory note, PVC-init troubleshooting) + `k8s/secrets.env.example` + the explicit `k8s/secrets.env` gitignore entry.
9. **Design doc → implemented + Implementation Notes**, house pattern, including whatever steps 3-7 prove wrong about this document.

# 14. Testing / Verification Plan

| Check | How |
|---|---|
| Single-command deploy | Fresh namespace, `kubectl apply -k k8s/` → everything Ready without manual intervention (secrets file being the documented one-time setup) |
| Trust boundary is real | From the host: gateway route works via `http://localhost` (hostless Ingress rule); direct requests to any of the seven services' former ports all fail. Audit per §9: `kubectl get svc -n skybook` (all ClusterIP — explicitly no NodePort/LoadBalancer/externalIPs), `kubectl get ingress -n skybook` (exactly one, api-gateway only), `kubectl get svc -A` (the only LoadBalancer in the whole cluster is ingress-nginx's controller in its own namespace) |
| Probes behave differently | Kill Postgres → DB-backed pods go NotReady (readiness includes `db` per §7's explicit groups) but do NOT restart (liveness excludes it); gateway/notification stay Ready throughout (no DB in their readiness); restore → Ready again with zero restarts counted |
| Kafka-outage isolation | Kill Kafka → NO pod goes NotReady or restarts (Kafka deliberately in neither health group, §7); consumers recover when it returns — the resilience branch's degradation model, now probe-verified |
| End-to-end business flow | register → login → protected call via Ingress, same evidence bar as every prior branch |
| Kafka path in-cluster | The poison-pill-to-DLT check re-run against the in-cluster broker |
| Observability parity | 8/8 Prometheus targets via pod discovery; a trace spanning gateway→flight in Tempo; pod logs shipped by Alloy, queryable in Loki by namespace/pod labels |
| Resource sanity | `kubectl top pods` (metrics-server, if present on Docker Desktop) or Grafana: no pod pinned at its memory limit at idle |
| Compose unbroken | `docker compose up` still fully green after this branch (nothing in it should touch compose, verified anyway) |
