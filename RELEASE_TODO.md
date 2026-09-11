# Release Readiness TODO — Maven Central + release process

Follows the post-P0–P7 cleanup pass (`CLEANUP_TODO.md`, done). Goal: publish
`rv32emu-core` to Maven Central and establish a repeatable manual release process.

Baseline: `./mvnw clean verify` green — 259 core + 1 cli tests, SpotBugs/Checkstyle
clean, CLI smoke passes, on JUnit 6.1.3. No git tags, no release history. Every
commit on `main` currently claims version `0.1.0` (not a SNAPSHOT).

## Decisions locked in (2026-09-10)

| Question | Decision |
|---|---|
| groupId / namespace | Keep **`com.alienspacebunny`**. Nate owns the domain and started Central Portal registration at some point — needs to be confirmed/finished (R1). |
| What publishes to Central | **`rv32emu-core` only.** |
| CLI distribution | **`rv32emu-cli` fat jar → GitHub Releases** as an asset, not a Maven artifact. Avoids the duplicate-classes problem (shade bundles core's classes; a published cli POM would also declare core as a dependency). |
| Versioning scheme | **UNRESOLVED — see R2.** Need to decide SemVer policy, SNAPSHOT-on-main, and pick the first published version. |

## Open questions

- **R2 versioning:** current state is `0.1.0` hardcoded in all three poms with no
  tag. Proposed: `main` carries `0.2.0-SNAPSHOT`; release versions exist only on
  annotated tags (`v0.2.0`); post-release bump commits the next `-SNAPSHOT`.
  First Central release could be `0.1.0` (retroactive) or start at `0.2.0`.
  **Needs Nate's call.**
- Does the published API count as stable (1.0.0) yet, or stay 0.x while the
  multi-hart feature work (`docs/FEATURE_REQUEST_PLAN.md`) lands? The feature plan
  is explicitly additive/backward-compatible, so 1.0.0 now is defensible.

---

## R1 — Central Portal account / namespace verification  *(blocker, Nate)*

- Confirm the `com.alienspacebunny` namespace registration on the **Central Portal**
  (central.sonatype.com — the modern path; legacy OSSRH / `oss.sonatype.org` is
  sunset and does not apply to new namespaces).
- Verify domain ownership via the DNS TXT challenge the Portal issues.
- Generate a Portal **publishing token** (user token) for CI / local deploy auth;
  store in `~/.m2/settings.xml` under the Portal server id, never in the repo.

## R2 — GPG signing key  *(blocker, Nate)*

- Generate or designate a GPG key for artifact signing.
- Publish the public key to a keyserver (`keys.openpgp.org` and/or
  `keyserver.ubuntu.com`) — Central validates signatures against these.
- Make the key + passphrase available to the release environment (local
  `gpg-agent`, or CI secret).

## R3 — POM metadata for Central  *(code — namespace-independent, can do now)*

Central rejects artifacts missing required metadata. Add to the **parent** pom
(inherited by modules): `<url>`, `<licenses>` (MIT), `<developers>` (Nate Edel),
`<scm>` (github.com/nkedel/rv32ima-java), `<organization>`/`<inceptionYear>`
optional. Add `<name>` + `<description>` to **each** module pom (these do **not**
inherit).

- [ ] parent `pom.xml`: `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, `<scm>`
- [ ] `core/pom.xml`: `<name>`, `<description>`
- [ ] `cli/pom.xml`: `<name>`, `<description>` (harmless even though cli is not published)
- [ ] LICENSE file: currently reads `Copyright (c) 2022 CNLohr`. Decide whether to
      add a `Copyright (c) 2025 Nate Edel` line for the port (README already claims
      the port is MIT). Keep the upstream attribution line either way.

## R4 — Deploy plugin wiring  *(code — needs R1 to test)*

- Add the **Central Publishing Maven Plugin**
  (`org.sonatype.central:central-publishing-maven-plugin`) — pull the current
  version + config shape from the live Central docs at publish time; do not copy
  from memory. No `<distributionManagement>` needed with the Portal plugin.
- Add `maven-gpg-plugin` **inside a `release` profile only** — otherwise every
  local `./mvnw verify` demands the signing key.
- Put the publishing plugin in the same `release` profile.
- `rv32emu-cli` must be excluded from the deploy (`maven.deploy.skip=true` on the
  cli module, or don't apply the publishing plugin there).
- javadoc + source jars are already wired in the parent build — Central requires
  both; confirm they attach for `core`.

## R5 — Versioning mechanics  *(code — needs R2 decision)*

- Move `main` to `<version>X.Y.Z-SNAPSHOT</version>` per R2.
- Decide: hand-edit the version in the 3 poms, or adopt
  `versions:set` / the `maven-release-plugin`. Checkpoint says "no automated
  semantic versioning" — hand-edit + a documented checklist is the current intent.

## R6 — Fix / replace `release.sh`  *(code)*

Current `release.sh` is **weaker than `./mvnw clean verify`**: it runs
`clean test` then `install -DskipTests`, so Spotless, Checkstyle, SpotBugs, and
the packaged-CLI smoke test (all bound to `verify`) never run. It also hardcodes
`0.1.0` jar paths twice.

- [ ] Replace step 1+2 with `./mvnw clean verify` (single invocation, all gates).
- [ ] Derive jar paths/version from the pom rather than hardcoding `0.1.0`.
- [ ] Keep the baremetal rebuild + final validation run.
- [ ] Add the deploy step (`./mvnw -Prelease deploy`) gated behind an explicit flag
      or a separate script so a normal build never publishes.

## R7 — CI workflow  *(new — no `.github/workflows/` exists)*

- Add a GitHub Actions workflow: `./mvnw clean verify` on push / PR (JDK 25).
- Optional: a release workflow triggered on tag push that builds, signs, deploys
  to Central, and uploads the CLI fat jar to the GitHub Release.

## R8 — Release-process document  *(doc — write after R1–R7 shape is known)*

`docs/RELEASING.md`: branch/PR flow, version bump, changelog, tag naming (`vX.Y.Z`),
which gates must be green, `-Prelease deploy`, Central Portal "publish" step,
GitHub Release creation + CLI jar upload, post-release SNAPSHOT bump, smoke-test
of the published artifact from a clean `~/.m2`.

---

## Suggested order

1. **R3** (POM metadata) — no external dependency, unblocks nothing but needed anyway.
2. **R2 decision** (Nate) → **R5** (SNAPSHOT on main).
3. **R6** (release.sh) + **R7** (CI verify workflow) — independent of Central.
4. **R1 + R2 keys** (Nate) → **R4** (deploy wiring), tested against a real staging deploy.
5. **R8** (RELEASING.md) once the mechanics are proven end-to-end.
