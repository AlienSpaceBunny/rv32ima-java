# Release Readiness TODO — Maven Central + release process

Follows the post-P0–P7 cleanup pass (`CLEANUP_TODO.md`, done). Goal: publish
`rv32emu-core` to Maven Central and establish a repeatable manual release process.

Baseline: `./mvnw clean verify` green — 267 core + 1 cli tests, SpotBugs/Checkstyle
clean, CLI smoke passes, on JUnit 6.1.3. No git tags, no release history yet, but
`main` now carries `0.1.1-SNAPSHOT` and `maven-release-plugin` is wired (R5, done) —
see `docs/RELEASING.md` for the local versioning/tagging/build procedure. `0.1.0` is
deliberately skipped: that version number is already referenced by the downstream V-32
project, so the first release from this repo will be `0.1.1`.

## Recommendation: not yet (2026-09-10)

Hold the Central release until after multi-hart Phase 1–2. Reasons:

- **Central artifacts are immutable and permanent.** A published `0.1.0` can never
  be replaced or withdrawn.
- **The public API is about to move.** `docs/FEATURE_REQUEST_PLAN.md` Phase 1–2 add
  default methods and an `AccessContext` record to `MemoryBus`, add fields to
  `RV32IMAState`, add an `IsaConfig` constructor to `RV32IMACore`, and change how
  `misa` is derived. It's designed to be source-compatible, but it hasn't been
  built yet, and publishing now freezes today's surface — including known warts
  (`step()`'s 8-arg signature, the `ramOffset`/`ramSize` fetch-window params the
  plan itself calls a "legacy wart").
- **No API-freeze review has happened.** The C1–C7 pass was explicitly *not*
  comprehensive. Central publication deserves one deliberate "is this the API we
  want to commit to?" pass.
- **Release mechanics don't exist yet** — no SNAPSHOT discipline, no tags, no CI.
  First Central release with none of that in place is how immutable mistakes ship.
- **No demand signal.** Nobody is blocked waiting to `mvn` this. Interim consumers
  can use **JitPack** (`com.github.nkedel:rv32ima-java:<tag-or-SHA>`) with zero
  publishing setup, zero signing, and no permanence commitment.

Good sequence: land Phase 1–2 → API-freeze review → set up versioning + CI (R5–R7)
→ publish, straight at a considered `0.2.0` or `1.0.0`. Namespace registration
(the slow bureaucratic step) is already done, so there's no rush cost to waiting.

## Decisions locked in (2026-09-10)

| Question | Decision |
|---|---|
| Publish to Central now? | **No — see recommendation above.** Revisit after multi-hart Phase 1–2. |
| groupId / namespace | **`com.alienspacebunny`** — registered (so is `us.n8l`). Keep it. |
| What publishes to Central (eventually) | **`rv32emu-core` only.** |
| CLI distribution | **`rv32emu-cli` fat jar → GitHub Releases** as an asset, not a Maven artifact. Avoids the duplicate-classes problem (shade bundles core's classes; a published cli POM would also declare core as a dependency). |
| Interim dependency access | **JitPack**, on demand — no repo changes required. |
| Versioning mechanics | **Done (R5).** `main` carries `0.1.1-SNAPSHOT`; `maven-release-plugin` handles the release/next-SNAPSHOT bump and `vX.Y.Z` tagging. See `docs/RELEASING.md`. |
| First release number | ~~`0.1.1`~~ **`0.2.0`** (Nate, 2026-09-13 — see the Update below; supersedes the original `0.1.1` call). `0.1.0` is skipped regardless — already referenced by the downstream V-32 project. |
| Changelog | **`CHANGELOG.md`**, Keep a Changelog format, tracked per version. Maintenance instructions in `AGENTS.md`. |

## Open questions

- Does the published API count as stable (1.0.0) yet, or stay 0.x while the
  multi-hart feature work (`docs/FEATURE_REQUEST_PLAN.md`) lands? The feature plan
  is explicitly additive/backward-compatible, so 1.0.0 now is defensible.

## Update (2026-09-13): hold extended past this repo's own staging plan

`docs/FEATURE_REQUEST_PLAN.md`'s Phase 1–5 staging plan (this repo's side of the multi-hart
work) finished this session. That does **not** trigger the API-freeze review or move the
"first release number" decision below — **Nate's call:** the API stays deliberately unfrozen,
and every version up through whatever's current when this note is read is unstable, until the
**emulator repo's** side of the integration (the AP/IOP application actually consuming this
library as a multi-hart core) is done and has exercised the API in practice. Only then does an
API-freeze review make sense, and the target for the first real release changes from `0.1.1` to
**`0.2.0`** — reflecting that real, non-additive API shakeout may happen once a real consumer
exists, not just this repo's own compliance-test-driven design. `main` may accumulate several
more plain `-SNAPSHOT` bumps in the meantime (see `docs/RELEASING.md`); none of them are
release candidates.

---

## R1 — Central Portal account / namespace  *(done / Nate; token done 2026-09-25)*

- `com.alienspacebunny` and `us.n8l` are both already registered on the **Central
  Portal** (central.sonatype.com — the modern path; legacy OSSRH / `oss.sonatype.org`
  is sunset). Namespace verification is complete.
- Portal user token: **done (2026-09-25)** — stored as AlienSpaceBunny organization
  secrets `CENTRAL_USERNAME`/`CENTRAL_PASSWORD`, shared with `alienspacebunny-build`
  (which has already published through it). Never in the repo.

## R2 — GPG signing key  *(done, 2026-09-25)*

- **Done:** dedicated "AlienSpaceBunny Releases" RSA 4096 key
  (`1FDA871CDFBF25ABB316DF15DB609F7A3E8E3165`), public key on keyserver.ubuntu.com,
  private key + passphrase as organization secrets `GPG_PRIVATE_KEY`/`GPG_PASSPHRASE`.
- Generate or designate a GPG key for artifact signing.
- Publish the public key to a keyserver (`keys.openpgp.org` and/or
  `keyserver.ubuntu.com`) — Central validates signatures against these.
- Make the key + passphrase available to the release environment (local
  `gpg-agent`, or CI secret).

## R3 — POM metadata for Central  *(code — namespace-independent, can do now)*

Central rejects artifacts missing required metadata. Add to the **parent** pom
(inherited by modules): `<url>`, `<licenses>` (MIT), `<developers>` (Nate Edel),
`<scm>` (github.com/AlienSpaceBunny/rv32ima-java — moved from `nkedel` 2026-09-25, where the
repo now lives), `<organization>`/`<inceptionYear>`
optional. Add `<name>` + `<description>` to **each** module pom (these do **not**
inherit).

- [ ] parent `pom.xml`: `<name>`, `<description>`, `<url>`, `<licenses>`, `<developers>`, `<scm>`
- [ ] `core/pom.xml`: `<name>`, `<description>`
- [ ] `cli/pom.xml`: `<name>`, `<description>` (harmless even though cli is not published)
- [ ] LICENSE file: currently reads `Copyright (c) 2022 CNLohr`. Decide whether to
      add a `Copyright (c) 2025 Nate Edel` line for the port (README already claims
      the port is MIT). Keep the upstream attribution line either way.

## R4 — Deploy plugin wiring  *(done 2026-09-25 — untested for rv32emu until the first real release)*

Implemented via the inherited `central-release` profile from `alienspacebunny-parent`
(sources, `maven-gpg-plugin`, `central-publishing-maven-plugin` 0.11.0 — current at
wiring time) instead of a local `release` profile; `rv32emu-cli` is excluded with the
plugin's `excludeArtifacts`. The same profile has already published
`alienspacebunny-parent`/`-build-tools` 0.1.1 end to end. Original notes:

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

## R5 — Versioning mechanics  *(done)*

- `main` moved to `0.1.0-SNAPSHOT` in all 3 poms, then to `0.1.1-SNAPSHOT` once Nate
  confirmed the first release should be `0.1.1` (`0.1.0` is already referenced by the
  downstream V-32 project).
- Adopted `maven-release-plugin` (not hand-edited versions) — supersedes the earlier
  "no automated semantic versioning" checkpoint note. Configured `autoVersionSubmodules`,
  `tagNameFormat=v@{project.version}`, `pushChanges=false`, `localCheckout=true`,
  and `goals=install` on `perform` (no Central deploy wired yet — see R4).
  `pom.xml.*`/`release.properties` working files gitignored.
- Procedure documented in `docs/RELEASING.md` (supersedes this item's original scope,
  which folds into R8 below).

## R6 — Fix `release.sh`  *(done)*

Was **weaker than `./mvnw clean verify`**: ran `clean test` then `install -DskipTests`,
so Spotless, Checkstyle, SpotBugs, and the packaged-CLI smoke test (all bound to
`verify`) never ran. Also hardcoded `0.1.0` jar paths twice, which the R5 SNAPSHOT
bump would have broken outright.

- [x] Replaced steps 1–2 with a single `./mvnw clean verify`.
- [x] Jar paths now derived via `help:evaluate -Dexpression=project.version`.
- [x] Kept the baremetal rebuild + final validation run.
- [ ] Deploy step intentionally **not** added — `release.sh` validates a build, it
      doesn't publish one; see R4/R8 for the eventual deploy path.

## R7 — CI workflow  *(release workflow done 2026-09-25; verify-on-push deliberately deferred)*

`.github/workflows/release.yml` is manual-only (`workflow_dispatch`): prepare → Central →
draft GitHub Release with the CLI jar. Per Nate, the repo is not ready for push/PR CI yet.
Original notes:

- Add a GitHub Actions workflow: `./mvnw clean verify` on push / PR (JDK 25).
- Optional: a release workflow triggered on tag push that builds, signs, deploys
  to Central, and uploads the CLI fat jar to the GitHub Release.

## R8 — Release-process document  *(Central section done 2026-09-25; published-artifact smoke test still open)*

`docs/RELEASING.md` **done** for the local scope: version bump mechanics, tag naming
(`vX.Y.Z`), which gates must be green, `release:prepare`/`release:perform`/`rollback`,
GitHub push as an explicit final step. **Still open**, once R1/R2/R4 land: the Central
Portal "publish" step, `-Prelease deploy`, GitHub Release creation + CLI jar upload
(could be manual or via R7 CI), and a smoke-test of the *published* artifact from a
clean `~/.m2` (distinct from `release:perform`'s local-install smoke test, which only
proves the build, not that Central actually served it back).

---

## Suggested order

1. ~~**R3** (POM metadata)~~ done. ~~**R5** (versioning) + **R6** (release.sh) + **R8**
   local scope~~ done — see `docs/RELEASING.md`.
2. **R7** (CI verify workflow) — independent of Central, no blocker.
3. **R1 + R2 keys** (Nate) → **R4** (deploy wiring), tested against a real staging deploy.
4. **R8** Central-publish section, once R4 is proven end-to-end.
