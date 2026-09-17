# Fold fork maintenance

- Optimize daily use for the Z Fold 8 (`SM-F971U1`), retain the Z Fold 8 Ultra (`SM-F976U1`) as a regression target, and welcome other compatible devices. Follow `FOLD_PROFILE.md`; adapt to window geometry and font scale, not model-name gates.
- Update `FORK_CHANGES.md` in the same commit as every downstream fix, feature, default, release constraint, or operational-policy change.
- Keep entries concise and distinguish shipped `fold/main` behavior from unmerged candidates.
- Treat upstream as a selective reference; do not optimize for upstream submission or perform a wholesale sync/rebase without explicit instruction.
- Never commit APKs, signing material, credentials, device backups, recordings, or private host details.
