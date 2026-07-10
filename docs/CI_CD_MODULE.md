# ⚙️ SkyBook CI/CD — Design

---

## Project Information

| | |
|---|---|
| **Scope** | `.github/workflows/ci.yml` + a root-`pom.xml` test-phase split (unit vs. integration) + Sonar wiring |
| **Branch** | `feature/ci-cd` |
| **Status** | Frozen. Implementation starting per §14's build order. |

Goal: every push and pull request against `main` automatically proves the project compiles, passes its full test suite, meets a coverage/quality bar, and can still be built into the same Docker images `feature/dockerization` already defined — with no manual step. Today none of this exists (`.github/workflows/` is an empty placeholder), and running the tests or a Sonar scan is something a human does by hand, from memory.

---

# Table of Contents

1. [Overview](#1-overview)
2. [Load-Bearing Findings](#2-load-bearing-findings)
3. [Workflow Triggers](#3-workflow-triggers)
4. [Pipeline Shape](#4-pipeline-shape)
5. [Unit vs. Integration Test Split](#5-unit-vs-integration-test-split)
6. [JaCoCo](#6-jacoco)
7. [SonarQube / SonarCloud](#7-sonarqube--sonarcloud)
8. [Docker Image Build & Push](#8-docker-image-build--push)
9. ["Deploy" - Scope Decision](#9-deploy---scope-decision)
10. [Permissions](#10-permissions)
11. [Secrets Required](#11-secrets-required)
12. [Deferred / Out of Scope](#12-deferred--out-of-scope)
13. [Known Risks / Open Questions](#13-known-risks--open-questions)
14. [Build Order](#14-build-order)
15. [Testing / Verification Plan](#15-testing--verification-plan)

---

# 1. Overview

One GitHub Actions workflow, two jobs:

```
push (main, feature/**) / pull_request (main) / workflow_dispatch
        │
        ▼
┌─────────────────────────────────────────────────┐
│  job: build-and-verify (every trigger, §3)       │
│                                                   │
│  checkout → setup-java 21 → compile              │
│  → mvn clean verify                              │
│      (runs unit tests via surefire, THEN         │
│       integration tests via failsafe, in the     │
│       SAME lifecycle pass - not two mvn calls,   │
│       see §5)                                    │
│  → upload surefire/failsafe/JaCoCo reports       │
│    (if: always() - even on failure, §4)          │
│  → SonarCloud scan (mvn sonar:sonar)             │
└───────────────────┬───────────────────────────────┘
                     │ needs: build-and-verify
                     ▼ (only on real push to main, never PRs)
┌─────────────────────────────────────────────────┐
│  job: docker-build-push                          │
│  strategy: matrix (8 services, in parallel, §8)  │
│                                                   │
│  build one service's image → push to GHCR        │
│  (SHA tag + latest)                              │
└─────────────────────────────────────────────────┘
```

---

# 2. Load-Bearing Findings

Confirmed, not assumed:

1. **The repo is public** (`praveencloudlab/skybook`, confirmed via the GitHub API). This is the single biggest simplifying fact for this branch: SonarCloud's free tier applies automatically to public repos, GitHub Actions minutes are unmetered for public repos, and GHCR (`ghcr.io`) hosts public images free. None of the "which paid tier do we need" questions that would apply to a private repo apply here.
2. **`mvn test` already runs unit AND integration tests together, undifferentiated.** No `maven-failsafe-plugin` exists anywhere in the reactor - only `surefire` (via its implicit default binding), whose default include pattern `**/*Test.java` already matches the existing integration-test naming convention (`PaymentApiKafkaIntegrationTest.java`, `InventoryApiKafkaIntegrationTest.java`, `CheckInBookingEventKafkaIntegrationTest.java`, etc. - confirmed present in `booking-service`, `inventory-service`, `payment-service`, `checkin-service`). So today there is no way to run "just the unit tests" or "just the integration tests" separately - matching the user's requested pipeline shape (separate unit/integration stages) needs a real change, detailed in §5.
3. **Integration tests already self-skip without Docker**, via `@Testcontainers(disabledWithoutDocker = true)` on each service's `AbstractXIntegrationTest` base class (confirmed in `payment-service`, and the same pattern in the other three) - so they're written to *not* need a separate CI flag; they just need Docker to be present, which every `ubuntu-latest` GitHub-hosted runner already has pre-installed and running.
4. **No Sonar configuration exists anywhere** - no `sonar-project.properties`, no `sonar-maven-plugin` in any `pom.xml`. There's a `sonarqube:community` Docker container in the local Docker history (exited, 47 hours old at last check) - evidence of local manual exploration, but a container running on one person's machine isn't reachable by GitHub's hosted runners, so it can't be what CI talks to. This branch wires CI to SonarCloud instead (§7), not that local container.
5. **No Maven wrapper (`mvnw`) exists**, confirmed already in `feature/dockerization`'s own research and unchanged since. `ubuntu-latest` GitHub-hosted runners ship Maven pre-installed, so `actions/setup-java`'s built-in Maven (or the runner's own) is used directly, consistent with the Dockerfiles' own choice not to introduce `mvnw`.
6. **The 8 Dockerfiles and `docker-compose.yml` from `feature/dockerization` already exist and are already verified working** (merged to `main`). This branch's "Docker Build" stage reuses them exactly as-is - it does not redesign the images, only adds a CI step that runs the same `docker build -f <service>/Dockerfile ./backend` commands already proven by hand.
7. **Declaring a plugin only in `pluginManagement` does not make Maven run it.** `pluginManagement` supplies configuration/version *for when a plugin is also declared* in a module's real `<build><plugins>` list - it never triggers execution by itself. `spring-boot-maven-plugin`'s `repackage` binding worked in `feature/dockerization` purely because every service *already* declared that plugin in its own `build/plugins` (for the lombok-exclude config) - `pluginManagement` only supplied the missing `<executions>`. No module declares `maven-surefire-plugin` or `maven-failsafe-plugin` explicitly today, so the same trick would silently do nothing here. Corrected in §5: both plugins go into the root `pom.xml`'s actual `<build><plugins>` list, not only `pluginManagement`, so every child inherits the real declaration, not just a template for one.

---

# 3. Workflow Triggers

```yaml
on:
  push:
    branches: [main, 'feature/**']
  pull_request:
    branches: [main]
  workflow_dispatch: {}
```

- `push` to `main` and to any `feature/**` branch, not just `main` - during active development of this pipeline itself, iterating means pushing directly to `feature/ci-cd` and seeing a real run, not opening/updating a PR every time just to trigger one. Worth revisiting once the pipeline is stable (narrowing back to `main` + PR only), but left broad deliberately for now rather than guessed at as a permanent shape.
- `pull_request` targeting `main` - the actual gate a future branch-protection rule (§12) would depend on.
- `workflow_dispatch` - a manual "Run workflow" button in the Actions UI, for re-running against a specific branch without needing a new commit, useful for exactly the kind of "did my YAML change work" iteration this branch itself needs.

---

# 4. Pipeline Shape

`.github/workflows/ci.yml`, one `build-and-verify` job (steps run sequentially on one runner, one checkout - no need for separate GitHub Actions `job:` boundaries here, which would mean a fresh runner and re-uploading artifacts between jobs for no benefit at this scale):

| Step | Command | Notes |
|---|---|---|
| Checkout | `actions/checkout@v4` | |
| Set up JDK 21 | `actions/setup-java@v4`, `temurin` distribution | matches every module's `<java.version>21</java.version>` |
| Compile | `mvn -B compile` | fails fast on a compile error alone, before spending time on tests |
| Test (unit + integration) | `mvn -B clean verify` | **one single Maven invocation** - see §5 for why this must not be `mvn test` followed by a separate `mvn verify` |
| Upload test/coverage reports | `actions/upload-artifact@v4`, `if: always()` | surefire reports, failsafe reports, and JaCoCo HTML/XML - uploaded even if the test step failed, so a red run is actually debuggable from the Actions UI instead of just a log wall (§13 risk) |
| SonarCloud scan | `mvn -B sonar:sonar` | reads the JaCoCo XML this step's own predecessor just produced (§6/§7) |

`docker-build-push` is a genuinely separate job - different trigger condition (§8), doesn't need the Maven toolchain, and benefits from running as an 8-way matrix instead of a sequential loop.

---

# 5. Unit vs. Integration Test Split

**Decision: introduce `maven-failsafe-plugin`, configured against the naming convention that already exists - no test classes renamed.**

Two corrections from the first draft, both confirmed necessary before this is safe to implement:

**Both plugins must be declared in the root `pom.xml`'s real `<build><plugins>` list, not only `pluginManagement`** (finding §2.7) - `pluginManagement` alone would not run either plugin, since no child module declares `surefire`/`failsafe` itself the way every child already declares `spring-boot-maven-plugin`. Concretely, in `backend/pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <configuration>
                <excludes>
                    <exclude>**/*IntegrationTest.java</exclude>
                </excludes>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-failsafe-plugin</artifactId>
            <configuration>
                <includes>
                    <include>**/*IntegrationTest.java</include>
                </includes>
            </configuration>
            <executions>
                <execution>
                    <goals>
                        <goal>integration-test</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Being in the parent's actual `<build><plugins>`, every child module inherits both by normal Maven parent/child inheritance - no per-service edits, same *kind* of one-change fix as the dockerization branch's `spring-boot-maven-plugin` fix, just applied correctly this time (in `plugins`, not only `pluginManagement`).

**Run `mvn clean verify` as a single command, never `mvn test` followed by a separate `mvn verify`.** Maven's default lifecycle is cumulative - `verify` is downstream of `test`, so a `verify` invocation always executes `test` (and everything before it) again as part of the same lifecycle run. Calling `mvn test` and then `mvn verify` as two separate CI steps would silently re-run every unit test a second time inside the second call, for no benefit and roughly double the unit-test wall-clock time. One `mvn clean verify` runs `compile → test (surefire, unit only after the exclude above) → package → integration-test (failsafe) → verify (failsafe verify)` in one pass - unit and integration tests are still two distinct, separately-reported phases (their own log sections, their own report directories), they just don't need two Maven processes to get there. `clean` at the front avoids stale `target/` state (particularly relevant for JaCoCo's exec files, §6) leaking between runs.

This is additive and low-risk: the total set of tests that run is identical to what `mvn test` alone runs today (confirmed by the naming convention already being consistent across all four services that have integration tests) - nothing stops running, tests just get properly separated into unit-only (surefire) and integration-only (failsafe) phases within that one command.

---

# 6. JaCoCo

Already present at the root (`jacoco-maven-plugin`, `prepare-agent` + `report` bound to the `test` phase - from before this branch). Combining unit and integration coverage correctly needs the plugin's own IT-specific goals, not a manual merge step:

- `jacoco:prepare-agent` (existing, phase `initialize`) - sets the `argLine` surefire uses, writes `target/jacoco.exec`. Unit coverage, unchanged.
- `jacoco:prepare-agent-integration` (new), bound to `pre-integration-test`, configured with `<propertyName>failsafeArgLine</propertyName>`, writing to a **separate** exec file (`target/jacoco-it.exec`) - unit and integration coverage must not collide in the same exec file mid-run.
- The `maven-failsafe-plugin` configuration (§5) needs `<argLine>${failsafeArgLine}</argLine>` added so the IT JaCoCo agent actually attaches to the forked JVM failsafe runs tests in - easy to add the plugin and forget this wiring, so it's called out explicitly here rather than left implicit.
- `jacoco:report` (existing) stays bound to reading `target/jacoco.exec` → unit coverage HTML/XML, unchanged location.
- `jacoco:report-integration` (new), bound to `verify`, reads `target/jacoco-it.exec` → a separate IT coverage report (`target/site/jacoco-it/`).
- **Sonar reads both XML reports directly** - `sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml,target/site/jacoco-it/jacoco.xml` (comma-separated, a standard Sonar-Maven-plugin property). Sonar aggregates coverage from both paths itself; no separate "merge the two exec files into one" step is needed just to get one combined number in the Sonar dashboard.
- Per-module reports only (`target/site/jacoco/index.html` per service, matching what every service's own README already references) - no new cross-module aggregator module. Adding one is a nicety, not something blocking "every push proves the project healthy," and is easy to bolt on later without revisiting this decision.

---

# 7. SonarQube / SonarCloud

**Decision: SonarCloud, not a self-hosted SonarQube instance.** A self-hosted server needs to be reachable from GitHub's hosted runners over the internet, which means either paying for/managing a publicly-reachable server, or switching to (paid) GitHub-hosted self-hosted-runners-in-your-network - disproportionate infrastructure for a portfolio project when SonarCloud's free tier already covers public repos with zero server to run. The local `sonarqube:community` container found in Docker history (finding §2.4) stays useful for a developer's own local ad hoc scans; it's just not what CI talks to.

- `mvn sonar:sonar` run once, from `backend/` (the reactor root) after `mvn clean verify` (§5) - Sonar's Maven plugin auto-discovers every module and (per §6) both JaCoCo XML reports, submitting one combined analysis for the whole reactor rather than 9 separate Sonar projects.
- Requires external, one-time manual setup this doc can't do on the user's behalf: sign up at sonarcloud.io with the GitHub account, import `praveencloudlab/skybook`, note the generated `sonar.organization` and `sonar.projectKey`, generate a token, add it as the `SONAR_TOKEN` GitHub Actions secret. Documented precisely in the PR/setup notes so it's a checklist, not tribal knowledge.
- Quality gate: use SonarCloud's default ("Sonar way") to start: don't invent custom thresholds with no historical data behind them. Revisit once a few weeks of real scans exist to calibrate against.

---

# 8. Docker Image Build & Push

- Reuses the 8 `backend/<service>/Dockerfile`s and the `context: ./backend` pattern exactly as `feature/dockerization` built and verified them - no new Dockerfile logic.
- **Parallel matrix, not a sequential loop.** `strategy: matrix: service: [api-gateway, auth-service, booking-service, checkin-service, flight-service, inventory-service, notification-service, payment-service]` - all 8 images build concurrently on 8 separate runner instances rather than one job building them one after another. Each matrix job runs the same three steps (`docker/login-action` against GHCR, `docker build`, `docker push`) parameterized on `${{ matrix.service }}`.
- **GHCR (`ghcr.io/praveencloudlab/skybook-<service>`), not Docker Hub.** Authenticates with the workflow's own built-in `GITHUB_TOKEN` (scoped `packages: write` for just this job, §10) - no separate registry account, no extra secret to create or rotate. Docker Hub would need a separate account + PAT stored as a secret for no benefit here.
- **Runs only on push to `main`, never on `pull_request`.** Building 8 images on every PR is real CI minutes spent on images that would immediately be thrown away if the PR isn't merged (and a PR from a fork can't safely be trusted with registry push credentials anyway - `GITHUB_TOKEN` from a fork's PR run is deliberately read-only). Tagging: the image tag is the commit SHA (traceable to an exact commit) plus a floating `latest` tag updated on every successful `main` push - the immutable-SHA-plus-latest pair `feature/kubernetes` will need as its deployment handoff.

---

# 9. "Deploy" - Scope Decision

The user's requested pipeline lists `Docker Build → Deploy` as the last two stages. **Decision: "Deploy" in this branch means "push the built image to GHCR" - nothing runs it anywhere.** There is no live environment to deploy *to* yet: no Kubernetes cluster, no cloud hosting account, nothing beyond the local `docker compose up` this project already has. Building a deploy step with no real target to point it at would mean inventing infrastructure this doc has no evidence the user has, or wants yet.

This isn't a gap being quietly dropped - it's exactly what `feature/kubernetes` (next-but-one on the project's own roadmap, after `feature/observability` and `feature/resilience`) is for: Deployments, Services, Ingress, ConfigMaps, Secrets. Once that exists, this workflow's docker-build-push job is the natural place to add a final `kubectl apply` / `helm upgrade` step - the image is already built and pushed by then, so that future branch only adds the last step, not this whole pipeline.

---

# 10. Permissions

Explicit least-privilege `permissions:` blocks, not the (org-dependent) default:

```yaml
permissions:
  contents: read

jobs:
  build-and-verify:
    permissions:
      contents: read
  docker-build-push:
    permissions:
      contents: read
      packages: write   # GHCR push only - nothing else needs it
```

Nothing in this workflow opens issues, comments on PRs, or writes to any other GitHub API surface, so no other scope (`issues`, `pull-requests`, `actions`, `id-token`, etc.) is granted. `packages: write` is scoped to the `docker-build-push` job specifically, not the whole workflow - `build-and-verify` never needs it.

---

# 11. Secrets Required

| Secret | Used for | Set up by |
|---|---|---|
| `SONAR_TOKEN` | `mvn sonar:sonar` authentication | User, via sonarcloud.io (§7) - cannot be automated from here |
| `GITHUB_TOKEN` | GHCR push | Automatic - GitHub provides this to every workflow run, no setup needed |

Notably absent: `JWT_SECRET`, `MAIL_USERNAME`/`MAIL_PASSWORD`, etc. None of the application's own runtime secrets (§8 of `DOCKERIZATION_MODULE.md`) are needed here - Docker image *build* never starts a container, it only compiles and packages a jar into an image layer, so none of the `docker-compose.yml` environment variables come into play until something actually runs that image.

---

# 12. Deferred / Out of Scope

- **Actually running `kubectl apply`/deploying anywhere** - see §9; blocked on `feature/kubernetes` existing at all.
- **Cross-module aggregate JaCoCo report** - per-module reporting is what exists today and is sufficient to start (§6).
- **Custom Sonar quality gate thresholds** - start with the default, tune later with real data (§7).
- **Build matrix / multi-JDK testing** - the whole fleet targets exactly Java 21 everywhere (confirmed, every module's `pom.xml`); testing against other JDK versions has no motivating use case here. (Not to be confused with the Docker-build matrix in §8, which is about parallelizing 8 *services*, not testing multiple JDKs.)
- **Caching Maven dependencies between workflow runs** (`actions/cache` for `~/.m2`) - a real speed win, genuinely low-risk to add, but deliberately left for the implementation step to add empirically once real run-time numbers exist to justify the cache-key strategy, rather than guessed at design time.
- **Branch protection rules requiring this workflow to pass before merge** - a GitHub repo *setting*, not a workflow file; the user enables this themselves once the workflow exists and has a few green runs behind it, exactly the kind of "verify it works, then tighten the gate" sequencing this project has followed throughout.

---

# 13. Known Risks / Open Questions

- **Integration tests need Docker-in-Docker on the runner.** `ubuntu-latest` GitHub-hosted runners have Docker pre-installed and already usable without extra setup (confirmed standard GitHub Actions runner behavior) - flagged here only because it's the one dependency this pipeline has that isn't just "Java is installed," and worth confirming empirically on the very first real workflow run rather than assumed silently.
- **SonarCloud's free tier for public repos could change its terms** - out of this project's control; if it ever stopped being free, the fallback is either paying for it or dropping the Sonar stage, not a design problem with this doc.
- **GHCR images are public by default for a public repo** - anyone can pull them once pushed. Acceptable for a portfolio project (nothing in the images is secret; runtime secrets are injected via environment variables at `docker run`/`docker compose up` time, never baked into the image), but worth stating rather than leaving implicit.
- **The failsafe/surefire split (§5) is a real behavior change to the build**, even though scoped to be low-risk - it needs the same "run the full reactor test suite before and after" verification this project has applied to every prior source change, not just a CI-only smoke test.
- **Forgetting the `failsafeArgLine` wiring (§6) would silently produce an empty/missing IT coverage report** rather than an error - exactly the kind of quiet gap this project's own review process (this document's revision included) exists to catch before it ships, not after.

---

# 14. Build Order

1. **Surefire/failsafe split** (§5) - root `pom.xml` change, in `<build><plugins>` (not `pluginManagement` alone, finding §2.7), verified locally first: `mvn clean verify` runs unit tests then integration tests exactly once each, combined test count matches what `mvn test` alone used to run on `main` before this branch.
2. **JaCoCo rebinding** (§6), including the `failsafeArgLine` wiring - verify both `target/site/jacoco/` (unit) and `target/site/jacoco-it/` (integration) get produced, on at least one service that has both (`payment-service`).
3. **Workflow triggers + permissions** (§3, §10) - the skeleton `ci.yml` before any real steps, so every subsequent step added is immediately tested against a real trigger on `feature/ci-cd` itself.
4. **`.github/workflows/ci.yml`: checkout, setup-java, compile step only** - simplest possible green run first, matching this project's own "verify each increment before adding the next" discipline.
5. **Add the `mvn clean verify` step + artifact upload (`if: always()`)** - verify Docker-on-runner works as expected for the integration tests (§13), and verify the upload step actually produces downloadable reports on a deliberately-broken test, not just on a passing run.
6. **SonarCloud setup** (external account work, §7) + the `sonar:sonar` step - first real scan reviewed manually on the SonarCloud dashboard before treating it as "done."
7. **`docker-build-push` job as an 8-way matrix** (§8) - verify all 8 images build in parallel and match what `docker compose build` already produces locally (same Dockerfiles, same result), and verify a draft PR does *not* trigger this job.
8. **Update this doc's status** to implemented + an Implementation Notes section, matching `API_GATEWAY_MODULE.md` §14 and `DOCKERIZATION_MODULE.md` §15's pattern - including, per this project's own established practice, an honest account of anything the first pass got wrong that a closer check caught (as happened with Kafka's replication factor in the dockerization branch, and with the `pluginManagement`-vs-`plugins` mistake this very design review caught before implementation even started).

---

# 15. Testing / Verification Plan

| Check | How |
|---|---|
| Full reactor test suite unaffected by the surefire/failsafe split | `mvn clean verify` locally, compare total test count against `mvn test` alone on `main` before this branch |
| Unit tests are not executed twice | Confirm surefire's test count in the `mvn clean verify` log appears once, not once inside an initial `test` phase and again inside a separate `verify` invocation |
| Workflow actually triggers | A real push to the `feature/ci-cd` branch (§3's broadened trigger makes this possible without a PR) - not just YAML that looks right |
| Integration tests actually run in CI, not silently skipped | Check the workflow's own log output for the specific test classes, not just a green checkmark - a `@Testcontainers(disabledWithoutDocker = true)` class silently skipping would still show green |
| Failed-run artifacts are actually downloadable | Deliberately break one test, push, and confirm the surefire/failsafe/JaCoCo artifacts are still uploaded and downloadable from the failed run (`if: always()`, §4) |
| SonarCloud scan is real and shows combined coverage | The SonarCloud project dashboard shows an actual analysis with a timestamp matching the workflow run, and a coverage number that reflects both unit and integration tests, not just "step succeeded" in Actions |
| Docker images build in parallel and match local builds | Confirm the Actions UI shows 8 concurrent matrix jobs, and pull a pushed GHCR image to diff its behavior against the equivalent `docker compose build` image from `feature/dockerization` (e.g. same `/actuator/health` response) |
| PRs don't trigger image pushes | Open a real draft PR and confirm the `docker-build-push` job is skipped, not just configured to be skipped |
| Workflow permissions are actually least-privilege | Inspect the run's own permissions summary in the Actions UI (GitHub shows the effective token permissions per job) rather than trusting the YAML alone |
