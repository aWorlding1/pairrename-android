## What problem does this solve?

<!-- Describe the user-facing issue and why this change is needed. -->

## What changed?

<!-- Summarize implementation and behavior changes. -->

## Validation

- [ ] `bash verify_all.sh`
- [ ] Android debug build, if available (`./gradlew :app:assembleDebug`)
- [ ] Tested the affected workflow on copies / emulator / device, or explained why not

## Safety and privacy

- [ ] File-operation failures do not silently overwrite or guess file identity
- [ ] No private photos, personal data, local config, credentials, or signing keys are included
- [ ] Documentation and user-facing strings are updated where relevant
