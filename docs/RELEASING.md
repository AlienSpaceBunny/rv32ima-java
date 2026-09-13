# Releasing rv32ima-java

Scope: **local, git-tagged, Maven-installed releases only.** There is no Maven Central
deploy wired up yet — see `RELEASE_TODO.md` (R1–R4), which is deliberately on hold until
the **emulator repo's** side of the multi-hart integration is done (not just this repo's
own `docs/FEATURE_REQUEST_PLAN.md` staging plan, which finished 2026-09-13) and an
explicit API-freeze review has happened against a real consumer. This document covers
versioning + tagging mechanics (R5) and a lightweight local build/verify procedure; it is
not the eventual publish-to-Central process.

## Model

`main` always carries a `-SNAPSHOT` version (currently `0.1.2-SNAPSHOT`; expect several more
plain bumps before a real release — see `RELEASE_TODO.md`'s 2026-09-13 update). The first
real release will be `0.2.0`, whichever `-SNAPSHOT` `main` is on when it's eventually cut;
`0.1.0`/`0.1.1` are both skipped (`0.1.0` because that version number is already referenced
by the downstream V-32 project; `0.1.1` was an earlier, now-superseded target from before the
hold was extended past this repo's own staging plan). Cutting a release:

1. Bumps to the release version (drops `-SNAPSHOT`), commits.
2. Tags `vX.Y.Z`.
3. Bumps to the next `-SNAPSHOT`, commits.
4. Builds the tagged version from a clean checkout and installs it to your local
   `~/.m2/repository` — nothing is pushed or published anywhere by this process.

All of this is driven by `maven-release-plugin` (parent `pom.xml`), configured for this
repo as:

- **`tagNameFormat`**: `v@{project.version}` — tags look like `v0.2.0`.
- **`autoVersionSubmodules=true`** — `core` and `cli` inherit the parent's version, so
  you're only asked for one version number, not three.
- **`pushChanges=false`** — `release:prepare` commits and tags **locally only**. Pushing
  is a separate, explicit step (§5 below) so nothing reaches `origin` by surprise.
- **`localCheckout=true`** — `release:perform` builds from the local tag; you don't need
  to have pushed it to GitHub first.
- **`goals=install`** on `perform` — not the plugin's default `deploy`, since there's no
  Central deploy configured yet (see the comment in `pom.xml` next to this plugin).

## Prerequisites

- Working tree clean (`git status`), on `main`, up to date with `origin/main`.
- `./mvnw clean verify` green.
- No local-only commits you're not ready to have permanently referenced by a tag.
- `CHANGELOG.md`'s `[Unreleased]` section reflects what's actually shipping (see step 2).

## Procedure

### 1. Dry run first

Safe to run repeatedly; makes no commits, no tags, and touches no files that survive
(`release:clean` removes its working files: `release.properties`, `pom.xml.next`,
`pom.xml.tag`, `pom.xml.releaseBackup` — all gitignored). Confirms the plugin can resolve
versions and that the release build (`clean verify`, `release:prepare`'s default
`preparationGoals`) is green.

```bash
./mvnw release:prepare -DdryRun=true
./mvnw release:clean
```

Batch mode (`-B`) accepts the plugin's defaults (release version = current version minus
`-SNAPSHOT`; next development version = next patch `-SNAPSHOT`) without prompting — useful
for a quick check, but read the defaults before using it for a real release:

```bash
./mvnw -B release:prepare -DdryRun=true
```

### 2. Update the changelog

Before tagging, turn `CHANGELOG.md`'s `[Unreleased]` section into the section for the
version you're about to cut, and start a fresh empty `[Unreleased]` above it:

```markdown
## [Unreleased]

## [0.2.0] - 2026-XX-XX
...entries that were under Unreleased...
```

Commit this on its own (`git commit -am "Changelog for 0.2.0"`) — `release:prepare`'s
`preparationGoals` (`clean verify`) doesn't touch the changelog for you, and the release
commit it makes is a version-bump commit, not the right place to bury changelog content.

### 3. Prepare (real)

Interactively confirms the release version and the next development version (press enter
to accept the sensible defaults), runs `clean verify`, then commits and tags **locally**.

```bash
./mvnw release:prepare
```

To pick specific versions non-interactively instead of the defaults:

```bash
./mvnw release:prepare -DreleaseVersion=0.2.0 -DdevelopmentVersion=0.2.1-SNAPSHOT
```

At this point `git log` shows two new local commits (`[maven-release-plugin] prepare
release vX.Y.Z` and `[maven-release-plugin] prepare for next development iteration`) and
`git tag` shows `vX.Y.Z`. Nothing has been pushed.

### 4. Perform

Checks out the new tag into `target/checkout` and runs `install` there — a second,
independent build of exactly the tagged commit, installed to your local
`~/.m2/repository`.

```bash
./mvnw release:perform
```

Verify the installed artifact:

```bash
ls ~/.m2/repository/com/alienspacebunny/rv32emu-core/<version>/
```

The CLI fat jar for that same tag is at `target/checkout/cli/target/rv32emu-cli-<version>.jar`
after `perform` — this is what gets attached to a GitHub Release (manually, for now; see
`RELEASE_TODO.md` R7 for future CI automation).

### 5. Push, when you're satisfied

`release:prepare`/`perform` never touch `origin`. When you're ready to make the release
commits and tag visible on GitHub:

```bash
git push origin main --follow-tags
```

### If something goes wrong before you push

```bash
./mvnw release:rollback
```

Reverts the two prepare commits and deletes the local tag, leaving `main` as it was. If
`rollback` itself can't run (for example, you already amended something by hand), undo
manually:

```bash
git tag -d vX.Y.Z
git reset --hard <commit-before-prepare>
```

Never run rollback (or a manual reset) after step 5 has pushed — that rewrites published
history. Cut a new patch release instead.

## Validating a release build

`release.sh` runs the full quality-gated build (`./mvnw clean verify` — Spotless,
Checkstyle, SpotBugs, all tests, the packaged-CLI smoke test) and then a final check
against a freshly compiled baremetal test binary. It does not version, tag, or publish
anything. Run it standalone at any time, or from inside `target/checkout` after
`release:perform` to validate the tagged commit specifically:

```bash
./release.sh
```

## What's still open

- **Versioning scheme (RELEASE_TODO.md R5):** this document assumes standard SemVer
  patch/minor/major judgement calls at prepare time; there's no enforced policy yet.
- **CI (R7):** no `.github/workflows/` exists. `release:prepare`'s build step and
  `release.sh` are currently the only gates, run locally.
- **Publishing (R1–R4):** on hold. When it's time, `perform`'s `goals` changes from
  `install` to `deploy` (or a dedicated `release` profile adds `maven-gpg-plugin` and the
  Central Publishing plugin) — see `RELEASE_TODO.md`.
