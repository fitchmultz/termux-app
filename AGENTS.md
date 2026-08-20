# Fold fork maintenance

- Optimize for the supported `SM-F976U1` profile described in `FOLD_PROFILE.md`.
- Update `FORK_CHANGES.md` in the same commit as every downstream fix, feature, default, release constraint, or operational-policy change.
- Keep entries concise and distinguish shipped `fold/main` behavior from unmerged candidates.
- Never commit APKs, signing material, credentials, device backups, recordings, or private host details.
