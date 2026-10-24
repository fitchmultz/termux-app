# Fold fork maintenance

- Optimize daily use for the Z Fold 8 (`SM-F971U1`), retain the Z Fold 8 Ultra (`SM-F976U1`) as a regression target, and welcome other compatible devices. Follow `FOLD_PROFILE.md`; adapt to window geometry and font scale, not model-name gates.
- Update `FORK_CHANGES.md` in the same commit as every downstream fix, feature, default, release constraint, or operational-policy change.
- Keep entries concise and distinguish shipped `fold/main` behavior from unmerged candidates.
- Treat upstream as a selective reference; do not optimize for upstream submission or perform a wholesale sync/rebase without explicit instruction.
- Never commit APKs, signing material, credentials, device backups, recordings, or private host details.

## Standing dependency maintenance role

- Proactively and frequently review and update app dependencies, build tooling, Android SDK/NDK, bootstrap/default packages, and CI actions; individual bump requests are not required. Verify authoritative stable-release metadata, release notes, incompatibilities, and provenance before choosing versions. Record the audit and justify retained pins.
- Adopt useful modern APIs, conventions, patterns, and functionality offered by compatible stable releases rather than extending deprecated or outdated approaches. Avoid speculative churn, fabricated versions, and unapproved prereleases.
- Preserve the package identity/prefix, target SDK 28, minimum SDK 24, and ARM64-only profile unless the owner explicitly authorizes changing those constraints. Coordinate overlapping bootstrap/default-package work rather than replacing another writer's inputs.
- Roll updates through the existing read-only checks, full hosted tests, packaged metadata/ABI/signature verification, and reproducible unsigned-build gates. Keep signing isolated; integration, versioning, signed publication, backup/rollback readiness, and physical-device installation/validation remain separate owner-controlled release steps. Maintenance authorization does not authorize automatic merging or release publication.
- On ARM64 Termux, use Java 21 and the existing Android SDK for configuration/dependency reports; do not execute the SDK's x86_64 native tools. Use GitHub for full test/APK gates. Pass `-R fitchmultz/termux-app` to repository-scoped `gh` commands that support it because implicit repository detection can select upstream. For `gh api`, which has no `-R`, scope the REST endpoint or GraphQL lookup to the intended owner/repository; mutation node IDs must come from that scoped lookup.
