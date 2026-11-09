# PR43 native Kitty graphics evidence

This image-only ancillary branch does not contain application source or workflow configuration and does not modify the qualified feature or main refs.

- Before: native run42628156467, source4ecee689de7cb94f1e4dc090877d25e594240c05, artifact13680811883. Both API28/37 capture cases passed. **The whole baseline job failed an existing keyboard assertion; it is not a green baseline suite.** ZIP SHA2564da1b5714f1189f59454c15dcfddf5caf979c2c79226dba475fdb939184b51c0.
- After: native run42639240850, sourceef36c7e83a0f6949c67d504473243b92d7bb149e, artifact13686165827. Full native job and both configured reviews succeeded. 637/637 cases passed (56 XML; no failure/error/skip), including26 graphics and2 capture cases. ZIP SHA2561124e03bc2a0b7d822e5f802cf6dffaa1fb051b5ae2f90f7d729796ba63c1880.
- Both archives were downloaded and digest-verified. Original PNGs are unchanged. The comparison PNGs only add labels/borders around original pixels; no resizing. The baseline and final source use identical capture test/fixture bytes.
- Unchanged production Pi terminal-image.ts atcd74c5a3615635c61e68c52922debcda7ed53b51 generated all76,249 fixture bytes (SHA256103e4ba835a596fa145d95291039be840cc3c21d9c8abd7845125efcf154954e).

These are synthetic test contents rendered by the production Android parser/bitmap/native renderer, not physical Fold screenshots, an installed-phone Pi revision, or a full interactive session. No physical-install gate is added. Parent owns normal integration, required final merged-main CI, signing and delivery.

Source PR: https://github.com/fitchmultz/termux-app/pull/43
Before reports: https://github.com/fitchmultz/termux-app/actions/runs/42628156467/artifacts/13680811883
After reports: https://github.com/fitchmultz/termux-app/actions/runs/42639240850/artifacts/13686165827

Via Pi Durable
