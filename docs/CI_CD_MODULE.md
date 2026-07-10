# ⚙️ SkyBook CI/CD — Design

---

## Project Information

| | |
|---|---|
| **Scope** | `.github/workflows/ci.yml` + a root-`pom.xml` test-phase split (unit vs. integration) + Sonar wiring |
| **Branch** | `feature/ci-cd` |
| **Status** | Design draft — under review, not yet frozen |

Goal: every push and pull request against `main` automatically proves the project compiles, passes its full test suite, meets a coverage/quality bar, and can still be built into the same Docker images `feature/dockerization` already defined — with no manual step. Today none of this exists (`.github/workflows/` is an empty placeholder), and running the tests or a Sonar scan is something a human does by hand, from memory.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [Pipeline Shape](#3-pipeline-shape)
4. [Unit vs. Integration Test Split](#4-unit-vs-integration-test-split)
5. [JaCoCo](#5-jacoco)
6. [SonarQube / SonarCloud](#6-sonarqube--sonarcloud)
7. [Docker Image Build & Push](#7-docker-image-build--push)
8. ["Deploy" - Scope Decision](#8-deploy---scope-decision)
9. [Secrets Required](#9-secrets-required)
10. [Deferred / Out of Scope](#10-deferred--out-of-scope)
11. [Known Risks / Open Questions](#11-known-risks--open-questions)
12. [Build Order](#12-build-order)
13. [Testing / Verification Plan](#13-testing--verification-plan)

---

# 1. Overview

One GitHub Actions workflow, two jobs:

```
push / pull_request
        │
        ▼
┌─────────────────────────────────────────────┐
│  job: build-and-verify (every push + PR)     │
│                                               │
│  checkout → setup-java 21 → compile          │
│  → unit tests (surefire)                     │
│  → integration tests (failsafe, needs Docker,│
│    already available on ubuntu-latest)       │
│  → JaCoCo report (unit + integration merged) │
│  → SonarCloud scan                           │
└───────────────────┬───────────────────────────┘
                     │ needs: build-and-verify
                     ▼ (only on push to main, not PRs)
┌─────────────────────────────────────────────┐
│  job: docker-build-push                      │
│                                               │
│  build all 8 service images (Dockerfiles     │
│  from feature/dockerization) → push to GHCR  │
└─────────────────────────────────────────────┘
```

---

# 2. Load-Bearing Findings

Confirmed, not assumed:

1. **The repo is public** (`praveencloudlab/skybook`, confirmed via the GitHub API). This is the single biggest simplifying fact for this branch: SonarCloud's free tier applies automatically to public repos, GitHub Actions minutes are unmetered for public repos, and GHCR (`ghcr.io`) hosts public images free. None of the "which paid tier do we need" questions that would apply to a private repo apply here.
2. **`mvn test` already runs unit AND integration tests together, undifferentiated.** No `maven-failsafe-plugin` exists anywhere in the reactor - only `surefire` (via its implicit default binding), whose default include pattern `**/*Test.java` already matches the existing integration-test naming convention (`PaymentApiKafkaIntegrationTest.java`, `InventoryApiKafkaIntegrationTest.java`, `CheckInBookingEventKafkaIntegrationTest.java`, etc. - confirmed present in `booking-service`, `inventory-service`, `payment-service`, `checkin-service`). So today there is no way to run "just the unit tests" or "just the integration tests" separately - matching the user's requested pipeline shape (separate unit/integration stages) needs a real change, detailed in §4.
3. **Integration tests already self-skip without Docker**, via `@Testcontainers(disabledWithoutDocker = true)` on each service's `AbstractXIntegrationTest` base class (confirmed in `payment-service`, and the same pattern in the other three) - so they're written to *not* need a separate CI flag; they just need Docker to be present, which every `ubuntu-latest` GitHub-hosted runner already has pre-installed and running.
4. **No Sonar configuration exists anywhere** - no `sonar-project.properties`, no `sonar-maven-plugin` in any `pom.xml`. There's a `sonarqube:community` Docker container in the local Docker history (exited, 47 hours old at last check) - evidence of local manual exploration, but a container running on one person's machine isn't reachable by GitHub's hosted runners, so it can't be what CI talks to. This branch wires CI to SonarCloud instead (§6), not that local container.
5. **No Maven wrapper (`mvnw`) exists**, confirmed already in `feature/dockerization`'s own research and unchanged since. `ubuntu-latest` GitHub-hosted runners ship Maven pre-installed, so `actions/setup-java`'s built-in Maven (or the runner's own) is used directly, consistent with the Dockerfiles' own choice not to introduce `mvnw`.
6. **The 8 Dockerfiles and `docker-compose.yml` from `feature/dockerization` already exist and are already verified working** (merged to `main`). This branch's "Docker Build" stage reuses them exactly as-is - it does not redesign the images, only adds a CI step that runs the same `docker build -f <service>/Dockerfile ./backend` commands already proven by hand.

---

# 3. Pipeline Shape

`.github/workflows/ci.yml`, triggers: `push` (to `main`) and `pull_request` (targeting `main`).

| Stage | Tool | Runs on |
|---|---|---|
| Compile | `mvn -B compile` | every push + PR |
| Unit tests | `mvn -B test` (surefire only, post-§4 split) | every push + PR |
| Integration tests | `mvn -B verify` (failsafe; Docker already on the runner) | every push + PR |
| JaCoCo | `mvn -B jacoco:report` (bound to `verify`, after both test types) | every push + PR |
| SonarCloud | `mvn -B sonar:sonar` | every push + PR |
| Docker image build + push | `docker build` × 8, push to GHCR | push to `main` only |

Two jobs, not six - "stage" here means a labeled step for clear pass/fail visibility in the Actions UI, not a separate GitHub Actions `job:` (which would mean a fresh runner, a fresh checkout, and re-uploading build artifacts between jobs for no benefit at this scale). The Docker build/push genuinely is a separate job, because it has a different trigger condition (only real pushes to `main`, never PRs - see §7) and doesn't need the Maven build's toolchain at all.

---

# 4. Unit vs. Integration Test Split

**Decision: introduce `maven-failsafe-plugin`, configured against the naming convention that already exists - no test classes renamed.**

Added to `backend/pom.xml`'s `pluginManagement` (same place `spring-boot-maven-plugin`'s `repackage` binding was added in `feature/dockerization`, for the same reason: one change, inherited by every module that already declares the plugin):

- `maven-surefire-plugin`: add `<excludes><exclude>**/*IntegrationTest.java</exclude></excludes>` - so `mvn test` becomes unit-tests-only.
- `maven-failsafe-plugin`: add `<includes><include>**/*IntegrationTest.java</include></includes>`, bound to the standard `integration-test`/`verify` phases - so `mvn verify` runs exactly the tests `mvn test` now excludes.

This is additive and low-risk: the set of tests that run across `mvn test` + `mvn verify` combined is identical to what `mvn test` alone runs today (confirmed by the naming convention already being consistent across all four services that have integration tests) - nothing stops running, tests just get split into two labeled stages.

---

# 5. JaCoCo

Already present at the root (`jacoco-maven-plugin`, `prepare-agent` + `report` bound to the `test` phase - from before this branch). Once unit and integration tests are two different Maven invocations (§4), coverage from both needs to land in one report:

- Add a second execution, `prepare-agent-integration`, bound to `pre-integration-test`, writing to a separate exec file (`jacoco-it.exec`) - the standard JaCoCo pattern for combined unit+IT coverage.
- Move the `report` execution's phase from `test` to `verify`, after both test types have run, and point it at both exec files (`report-aggregate`-style merge).
- Per-module reports only (`target/site/jacoco/index.html` per service, matching what every service's own README already references) - no new cross-module aggregator module. Adding one is a nicety, not something blocking "every push proves the project healthy," and is easy to bolt on later without revisiting this decision.

---

# 6. SonarQube / SonarCloud

**Decision: SonarCloud, not a self-hosted SonarQube instance.** A self-hosted server needs to be reachable from GitHub's hosted runners over the internet, which means either paying for/managing a publicly-reachable server, or switching to (paid) GitHub-hosted self-hosted-runners-in-your-network - disproportionate infrastructure for a portfolio project when SonarCloud's free tier already covers public repos with zero server to run. The local `sonarqube:community` container found in Docker history (finding §2.4) stays useful for a developer's own local ad hoc scans; it's just not what CI talks to.

- `mvn sonar:sonar` run once, from `backend/` (the reactor root) after `mvn verify` - Sonar's Maven plugin auto-discovers every module and its JaCoCo XML report, submitting one combined analysis for the whole reactor rather than 9 separate Sonar projects.
- Requires external, one-time manual setup this doc can't do on the user's behalf: sign up at sonarcloud.io with the GitHub account, import `praveencloudlab/skybook`, note the generated `sonar.organization` and `sonar.projectKey`, generate a token, add it as the `SONAR_TOKEN` GitHub Actions secret. Documented precisely in the PR/setup notes so it's a checklist, not tribal knowledge.
- Quality gate: use SonarCloud's default ("Sonar way") to start: don't invent custom thresholds with no historical data behind them. Revisit once a few weeks of real scans exist to calibrate against.

---

# 7. Docker Image Build & Push

- Reuses the 8 `backend/<service>/Dockerfile`s and the `context: ./backend` pattern exactly as `feature/dockerization` built and verified them - no new Dockerfile logic.
- **GHCR (`ghcr.io/praveencloudlab/skybook-<service>`), not Docker Hub.** Authenticates with the workflow's own built-in `GITHUB_TOKEN` (`packages: write` permission) - no separate registry account, no extra secret to create or rotate. Docker Hub would need a separate account + PAT stored as a secret for no benefit here.
- **Runs only on push to `main`, never on `pull_request`.** Building 8 images on every PR is real CI minutes spent on images that would immediately be thrown away if the PR isn't merged (and a PR from a fork can't safely be trusted with registry push credentials anyway - `GITHUB_TOKEN` from a fork's PR run is deliberately read-only). Tagging: the image tag is the commit SHA (traceable to an exact commit) plus a floating `latest` tag updated on every successful `main` push.

---

# 8. "Deploy" - Scope Decision

The user's requested pipeline lists `Docker Build → Deploy` as the last two stages. **Decision: "Deploy" in this branch means "push the built image to GHCR" - nothing runs it anywhere.** There is no live environment to deploy *to* yet: no Kubernetes cluster, no cloud hosting account, nothing beyond the local `docker compose up` this project already has. Building a deploy step with no real target to point it at would mean inventing infrastructure this doc has no evidence the user has, or wants yet.

This isn't a gap being quietly dropped - it's exactly what `feature/kubernetes` (next-but-one on the project's own roadmap, after `feature/observability` and `feature/resilience`) is for: Deployments, Services, Ingress, ConfigMaps, Secrets. Once that exists, this workflow's docker-build-push job is the natural place to add a final `kubectl apply` / `helm upgrade` step - the image is already built and pushed by then, so that future branch only adds the last step, not this whole pipeline.

---

# 9. Secrets Required

| Secret | Used for | Set up by |
|---|---|---|
| `SONAR_TOKEN` | `mvn sonar:sonar` authentication | User, via sonarcloud.io (§6) - cannot be automated from here |
| `GITHUB_TOKEN` | GHCR push | Automatic - GitHub provides this to every workflow run, no setup needed |

Notably absent: `JWT_SECRET`, `MAIL_USERNAME`/`MAIL_PASSWORD`, etc. None of the application's own runtime secrets (§8 of `DOCKERIZATION_MODULE.md`) are needed here - Docker image *build* never starts a container, it only compiles and packages a jar into an image layer, so none of the `docker-compose.yml` environment variables come into play until something actually runs that image.

---

# 10. Deferred / Out of Scope

- **Actually running `kubectl apply`/deploying anywhere** - see §8; blocked on `feature/kubernetes` existing at all.
- **Cross-module aggregate JaCoCo report** - per-module reporting is what exists today and is sufficient to start (§5).
- **Custom Sonar quality gate thresholds** - start with the default, tune later with real data (§6).
- **Build matrix / multi-JDK testing** - the whole fleet targets exactly Java 21 everywhere (confirmed, every module's `pom.xml`); testing against other JDK versions has no motivating use case here.
- **Caching Maven dependencies between workflow runs** (`actions/cache` for `~/.m2`) - a real speed win, genuinely low-risk to add, but deliberately left for the implementation step to add empirically once real run-time numbers exist to justify the cache-key strategy, rather than guessed at design time.
- **Branch protection rules requiring this workflow to pass before merge** - a GitHub repo *setting*, not a workflow file; the user enables this themselves once the workflow exists and has a few green runs behind it, exactly the kind of "verify it works, then tighten the gate" sequencing this project has followed throughout.

---

# 11. Known Risks / Open Questions

- **Integration tests need Docker-in-Docker on the runner.** `ubuntu-latest` GitHub-hosted runners have Docker pre-installed and already usable without extra setup (confirmed standard GitHub Actions runner behavior) - flagged here only because it's the one dependency this pipeline has that isn't just "Java is installed," and worth confirming empirically on the very first real workflow run rather than assumed silently.
- **SonarCloud's free tier for public repos could change its terms** - out of this project's control; if it ever stopped being free, the fallback is either paying for it or dropping the Sonar stage, not a design problem with this doc.
- **GHCR images are public by default for a public repo** - anyone can pull them once pushed. Acceptable for a portfolio project (nothing in the images is secret; runtime secrets are injected via environment variables at `docker run`/`docker compose up` time, never baked into the image), but worth stating rather than leaving implicit.
- **The failsafe/surefire split (§4) is a real behavior change to the build**, even though scoped to be low-risk - it needs the same "run the full reactor test suite before and after" verification this project has applied to every prior source change, not just a CI-only smoke test.

---

# 12. Build Order

1. **Surefire/failsafe split** (§4) - root `pom.xml` change, verified locally first (`mvn test` runs only unit tests, `mvn verify` runs only integration tests, combined test count matches what `mvn test` alone used to run) before touching CI at all.
2. **JaCoCo rebinding** (§5) - verify `target/site/jacoco/index.html` still gets produced and now reflects both unit and integration coverage, on at least one service that has both (`payment-service`).
3. **`.github/workflows/ci.yml`, compile + unit test steps only** - get the simplest possible green workflow run first, matching this project's own "verify each increment before adding the next" discipline.
4. **Add the integration-test step** - verify Docker-on-runner works as expected (§11), not assumed.
5. **SonarCloud setup** (external account work, §6) + the `sonar:sonar` step - first real scan reviewed manually before treating it as "done."
6. **`docker-build-push` job** - build all 8 images in CI, verify they match what `docker compose build` already produces locally (same Dockerfiles, same result).
7. **Update this doc's status** to implemented + an Implementation Notes section, matching `API_GATEWAY_MODULE.md` §14 and `DOCKERIZATION_MODULE.md` §15's pattern - including, per this project's own established practice, an honest account of anything the first pass got wrong that a closer check caught (as happened with Kafka's replication factor in the dockerization branch).

---

# 13. Testing / Verification Plan

| Check | How |
|---|---|
| Full reactor test suite unaffected by the surefire/failsafe split | `mvn test` + `mvn verify` locally, compare total test count against `mvn test` alone on `main` before this branch |
| Workflow actually triggers | A real push to the `feature/ci-cd` branch (or a draft PR) - not just YAML that looks right |
| Integration tests actually run in CI, not silently skipped | Check the workflow's own log output for the specific test classes, not just a green checkmark - a `@Testcontainers(disabledWithoutDocker = true)` class silently skipping would still show green |
| SonarCloud scan is real | The SonarCloud project dashboard shows an actual analysis with a timestamp matching the workflow run, not just "step succeeded" in Actions |
| Docker images match local builds | Pull a pushed GHCR image and diff its behavior against the equivalent `docker compose build` image from `feature/dockerization` (e.g. same `/actuator/health` response) |
| PRs don't trigger image pushes | Open a real draft PR and confirm the `docker-build-push` job is skipped, not just configured to be skipped |
