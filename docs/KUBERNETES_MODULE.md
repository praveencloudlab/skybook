# ☸️ SkyBook Kubernetes — Design

---

## Project Information

| | |
|---|---|
| **Scope** | Kubernetes manifests for the full platform: Deployments, ClusterIP Services, Ingress, ConfigMaps, Secrets, health probes, resource limits — only the API Gateway publicly exposed |
| **Branch** | `feature/kubernetes` |
| **Status** | Design draft — under review, not yet frozen |

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
- **The observability quintet** (Prometheus, Grafana, Loki, Tempo, Promtail) — Deployments + PVCs, except Promtail which becomes a **DaemonSet** (the k8s-native form of "tail every container's logs on every node").
- **ClusterIP Services everywhere; one Ingress** routing to the gateway. Nothing else is reachable from outside the cluster.
- **ConfigMaps** for topology config (service DNS names replace compose service names), **Secrets** for the same four values `.env` holds today.

**Target cluster: Docker Desktop's built-in Kubernetes** — kubectl v1.34.1 and kustomize are already on the machine (confirmed); no cluster exists yet (no contexts, no minikube/kind/helm). Enabling it is one checkbox (Docker Desktop → Settings → Kubernetes → Enable) — the user's single external prerequisite for this branch, same category as the SonarCloud token was for CI.

---

# 2. Load-Bearing Findings

Confirmed, not assumed:

1. **No cluster or cluster tooling exists yet** — `kubectl config get-contexts` is empty; no helm/minikube/kind installed. kubectl v1.34.1 with built-in kustomize v5.7.1 is present. This drives two decisions: Docker Desktop K8s as the target (zero new installs beyond the checkbox — the Docker Desktop it ships in is already the project's runtime), and Kustomize over Helm (§4 — the tool is already in hand, and there is exactly one environment to render).
2. **The GHCR images are public and complete.** CI pushes all 8 services with `latest` + commit-SHA tags on every `main` merge, verified pullable anonymously (done live during the CI/CD branch). The cluster needs no `imagePullSecrets`, no registry auth, no local builds — deployment consumes exactly the artifact the pipeline produces, the handoff the CI/CD design doc (§8) planned for.
3. **Spring Boot auto-enables Kubernetes liveness/readiness probes in-cluster.** When Boot detects the k8s environment (via `KUBERNETES_SERVICE_HOST`), `/actuator/health/liveness` and `/actuator/health/readiness` activate automatically — the `health` endpoint every service already exposes gains the probe sub-groups with **zero application changes**. Compose's single `curl -f /actuator/health` check becomes two properly-differentiated probes (§7).
4. **Every piece of environment-specific config already flows through env vars** — the dockerization branch's whole design (`SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `*_BASE_URL`, `OTEL_*` via relaxed binding). Kubernetes ConfigMaps/Secrets feed the same variables with cluster-DNS values (`http://flight-service:8082` stays literally identical — compose service names and k8s Service names are both DNS). **Zero application code or config-file changes in this branch**, same bar as dockerization.
5. **The compose file is the authoritative topology inventory** — every env var, dependency edge, healthcheck, volume, and the four secrets (`JWT_SECRET`, `MAIL_USERNAME`/`MAIL_PASSWORD`, `CHECKIN_BOARDING_PASS_KEY`, `GRAFANA_ADMIN_PASSWORD`) transcribe mechanically. The subtle exceptions that do NOT transcribe mechanically: the `otel-agent` one-shot download container (compose shared volume → k8s **initContainer + emptyDir per pod**, §5), Promtail's Docker-socket discovery (→ DaemonSet with node log paths, §10), and Kafka's advertised listeners (compose hostname → headless-Service DNS, §5).
6. **Docker Desktop K8s ships no Ingress controller.** `ingress-nginx` is installed once from its official published manifest (version pinned in our repo as `k8s/ingress-nginx/` copy, not a live URL fetch at apply time) — Docker Desktop supports `LoadBalancer`-type services on localhost, which is how ingress-nginx's own front door binds to `http://localhost`.

---

# 3. Architecture

```
                       host (browser / Postman)
                              │  http://localhost  (only entry point)
                              ▼
                    ingress-nginx (LoadBalancer)
                              │  Ingress: skybook.local... → api-gateway:8080
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
   │   promtail (DaemonSet, node log paths)                                  │
   └──────────────────────────────────────────────────────────────────────────┘
```

The compose stack remains fully supported for day-to-day dev — this branch adds a deployment target, it does not replace `docker compose up`.

# 4. Manifest Strategy: Kustomize, No Helm

Plain YAML manifests under `k8s/base/`, tied together by a `kustomization.yaml`, applied with the kubectl that's already installed (`kubectl apply -k`). **Not Helm**: Helm isn't installed, templating buys nothing with a single environment and zero chart consumers, and every value a chart would parameterize is already a ConfigMap/Secret. Kustomize's patch/overlay model is there for free the day a second environment (a real cloud cluster) appears — `k8s/overlays/<env>/` is the natural extension point, deliberately not created until it has a real member.

One manifest file per component (`k8s/base/booking-service.yaml` holding its Deployment + Service together), mirroring how the compose file groups per-service concerns — greppable, reviewable, no cross-file hunting for a service's full definition.

# 5. Workloads

| Component | Kind | Notes |
|---|---|---|
| 8 app services | Deployment (replicas: 1) + ClusterIP Service | Image `ghcr.io/praveencloudlab/skybook-<name>:latest` for dev (SHA-pinning noted in §12); OTel agent via **initContainer** (`curlimages/curl`, checksum-verified download into an `emptyDir` mounted at `/otel`) — the k8s-idiomatic translation of compose's one-shot shared-volume container, per-pod instead of shared |
| postgres | StatefulSet (replicas: 1) + headless Service + PVC | `postgres:16-alpine`; init SQL (six databases) via ConfigMap mounted at `/docker-entrypoint-initdb.d` — same file as `docker/postgres/` |
| kafka | StatefulSet (replicas: 1) + headless Service + PVC | `apache/kafka:3.9.0`, same KRaft env block as compose including the replication-factor-1 settings (the consumer-offsets lesson carries over verbatim); `KAFKA_ADVERTISED_LISTENERS` becomes the pod's stable StatefulSet DNS name (`kafka-0.kafka.skybook.svc.cluster.local:9092`) — the one env value that is *not* a mechanical copy |
| prometheus, grafana, loki, tempo | Deployment (replicas: 1) + ClusterIP Service + PVC each | Configs via ConfigMaps (same files as `docker/<component>/`); Grafana provisioning ConfigMaps mirror `docker/grafana/` |
| promtail | DaemonSet + RBAC (ServiceAccount, ClusterRole for pod discovery) | `kubernetes_sd_configs` replaces compose's `docker_sd_configs`; reads `/var/log/pods` from the node — the standard k8s promtail deployment shape |

Replicas stay at 1 everywhere — same single-instance semantics as compose (and the gateway's in-memory rate limiter plus the Kafka partition counts all assume it; scaling out is a documented non-goal, §11).

# 6. Configuration & Secrets

- **One ConfigMap per app service** carrying its non-secret env block, transcribed from compose: datasource URLs (`jdbc:postgresql://postgres:5432/skybook_x`), `SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092`, `*_BASE_URL` values (service DNS — literally identical strings to compose), `OTEL_*` block, `SPRING_DATASOURCE_HIKARI_INITIALIZATION_FAIL_TIMEOUT`.
- **One `skybook-secrets` Secret** with the same four values `.env` holds. Created from a gitignored file (`k8s/secrets.env`, template `k8s/secrets.env.example` — the `env.example` pattern repeated), referenced via `envFrom`/`secretKeyRef`. Kustomize's `secretGenerator` handles creation so `kubectl apply -k` stays the single command. Plain base64 Secrets, honestly labeled: real secret *management* (sealed-secrets/Vault/SOPS) is `feature/security-hardening` territory (§11).
- No Spring profiles, no second `application.yml` anywhere — findings §2.4's env-var discipline carries the whole weight.

# 7. Health Probes

Per app service, using the Boot-auto-enabled probe endpoints (finding §2.3):

- **readinessProbe** → `/actuator/health/readiness` — gates Service traffic; a pod that can't reach its DB stops receiving requests without being killed.
- **livenessProbe** → `/actuator/health/liveness` — restarts genuinely hung JVMs; deliberately *excludes* external dependencies (Boot's default liveness group), so a dead Postgres doesn't cause restart storms — the exact failure-isolation lesson the mail-health-indicator bug taught in dockerization.
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
- Postgres/Kafka/observability get their own modest requests; Grafana/Promtail are tiny.

# 9. Ingress & the Trust Boundary

- One Ingress: `/` → `api-gateway:8080` on `localhost` (Docker Desktop binds ingress-nginx's LoadBalancer to the host). No TLS in this branch (localhost dev; TLS is meaningless theater without a real domain — `feature/security-hardening`/real-cloud concern).
- **No other Service is NodePort/LoadBalancer, no other Ingress rule exists.** This closes the loop `API_GATEWAY_MODULE.md` §8 opened: JWT enforcement at the gateway is now backed by actual network unreachability of the seven services, not by convention. Observability UIs (Grafana etc.) stay ClusterIP too — reached via `kubectl port-forward` (documented one-liners in the README section), keeping the "only the gateway is public" statement absolute rather than "except for four dashboards."
- Kustomize labels give every skybook object a common selector, making the "what is exposed?" audit a single `kubectl get svc,ingress -n skybook` whose expected output is written down in §14.

# 10. Observability on the Cluster

- Prometheus scrape config swaps static compose hostnames for **`kubernetes_sd_configs` with pod-annotation discovery** (`prometheus.io/scrape: "true"`, port/path annotations on each app pod) — the standard k8s pattern, and unlike a static list it survives pod restarts and future services automatically.
- Promtail's DaemonSet tails `/var/log/pods` with k8s metadata labels (namespace/pod/container) replacing compose's service label — Loki queries change shape slightly (`{namespace="skybook", pod=~"booking-service.*"}`); the fleet dashboard's Loki panel is updated accordingly.
- Tempo/Grafana/Loki configs are otherwise byte-identical ConfigMap mounts of the `docker/` files. The OTel env blocks on every pod are byte-identical too (`http://tempo:4317` resolves in-cluster exactly as in-compose).

# 11. Deferred / Out of Scope

- **Horizontal scaling / HPA / multiple replicas** — the gateway's in-memory rate limiter, single-partition topics, and single-node infra all assume 1 replica; scaling is real architectural work (Redis rate limiting, partitioned topics), not a `replicas: 3` edit. Documented, not attempted.
- **NetworkPolicies, PodSecurityStandards, non-root containers, TLS** — `feature/security-hardening`'s explicitly-planned scope; this branch establishes reachability boundaries only.
- **CD (auto-`kubectl apply` from CI)** — the CI/CD doc's §8 deferred deploy step; wire it once this branch proves the target works. Manual `kubectl apply -k` is the v1 deployment action.
- **Real secret management** (SOPS/sealed-secrets/Vault) — plain namespaced Secrets now, hardening branch later.
- **Cloud cluster / multi-node / storage classes beyond Docker Desktop's `hostpath`** — the kustomize overlay seam exists for when this matters.
- **Kafka/Postgres operators** — single-node StatefulSets are honest about what this is; operators earn their complexity at replication scale this project doesn't have.

# 12. Known Risks / Open Questions

- **`latest` tags in dev manifests** — convenient (always the newest merged image) but non-reproducible; `imagePullPolicy: Always` makes restarts pick up new images semi-implicitly. Accepted for the dev cluster with the SHA-tag alternative documented inline; a real environment overlay would pin SHAs (which CI already publishes).
- **Docker Desktop K8s shares the machine's Docker resources** — the full stack (8 JVMs + infra + observability) inside Docker Desktop's VM alongside the compose stack would double memory pressure; the README will say plainly: run one or the other, `docker compose down` before `kubectl apply`.
- **ingress-nginx on Docker Desktop occasionally fights Windows port 80 reservations** (HNS port exclusions) — flagged from research, verified empirically in build order step 2, with the port-8090-fallback documented if it bites.
- **All resource numbers are pre-load-test defaults** — same honesty clause as resilience thresholds; Grafana is the tuning feedback loop.
- **Promtail node-path assumptions** — `/var/log/pods` layout inside Docker Desktop's VM is the standard containerd layout, but verified empirically (build order step 6) rather than trusted, given this project's record of "the default isn't what the docs said."

# 13. Build Order

1. **User prerequisite: enable Docker Desktop Kubernetes** (Settings → Kubernetes → Enable; `kubectl get nodes` returns the node). Everything below is blocked on this single click.
2. **Namespace + ingress-nginx** — pinned manifest copy applied; verify the controller's LoadBalancer binds localhost (and catch the port-80 risk early).
3. **postgres + kafka StatefulSets** — verify six databases created, KRaft broker healthy, `__consumer_offsets` creatable (the replication-factor lesson re-verified in the new home).
4. **flight-service first** (the dockerization template pattern: simplest DB-only service) — Deployment/Service/ConfigMap + probes + limits; verify Ready 1/1, readiness gates work by breaking its DB config deliberately.
5. **Remaining 7 app services + the Ingress** — full request path: register → login → JWT-protected call through `http://localhost` → correct 401/200 behavior; verify the seven services are NOT reachable from the host by any path.
6. **Observability quintet** — Prometheus pod discovery finds 8 targets, Grafana provisioned dashboards render, Promtail ships pod logs, one OTel trace spans gateway→flight in Tempo (the §14 checks from the observability doc, re-run in-cluster).
7. **README deployment section** (`kubectl apply -k`, port-forward one-liners, compose-vs-k8s memory note, troubleshooting) + `k8s/secrets.env.example`.
8. **Design doc → implemented + Implementation Notes**, house pattern, including whatever steps 2-6 prove wrong about this document.

# 14. Testing / Verification Plan

| Check | How |
|---|---|
| Single-command deploy | Fresh namespace, `kubectl apply -k k8s/` → everything Ready without manual intervention (secrets file being the documented one-time setup) |
| Trust boundary is real | From the host: gateway route works via `http://localhost`; direct requests to any of the seven services' former ports all fail; `kubectl get svc,ingress -n skybook` shows exactly one non-ClusterIP entry (ingress-nginx's) |
| Probes behave differently | Kill Postgres → app pods go NotReady (readiness) but do NOT restart (liveness isolation); restore → Ready again with no restarts counted |
| End-to-end business flow | register → login → protected call via Ingress, same evidence bar as every prior branch |
| Kafka path in-cluster | The poison-pill-to-DLT check re-run against the in-cluster broker |
| Observability parity | 8/8 Prometheus targets via pod discovery; a trace spanning gateway→flight in Tempo; pod logs queryable in Loki by namespace/pod labels |
| Resource sanity | `kubectl top pods` (metrics-server, if present on Docker Desktop) or Grafana: no pod pinned at its memory limit at idle |
| Compose unbroken | `docker compose up` still fully green after this branch (nothing in it should touch compose, verified anyway) |
