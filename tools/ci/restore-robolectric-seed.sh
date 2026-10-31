#!/usr/bin/env bash
set -euo pipefail

repository="$(mktemp -d "$FOLD_CI_WORK/robolectric-repository.XXXXXXXX")"
artifact_dir="$repository/org/robolectric/nativeruntime-dist-compat/1.0.19"
base='https://github.com/fitchmultz/termux-app/releases/download/ci-robolectric-cache-1.0.19'
curl_args=(--disable --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --retry 3)
mkdir -p "$artifact_dir"

if ! curl "${curl_args[@]}" "$base/nativeruntime-dist-compat-1.0.19.pom" --output "$artifact_dir/nativeruntime-dist-compat-1.0.19.pom" ||
   ! curl "${curl_args[@]}" "$base/nativeruntime-dist-compat-1.0.19.jar" --output "$artifact_dir/nativeruntime-dist-compat-1.0.19.jar"; then
    rm -rf "$repository"
    echo '::warning::Robolectric seed unavailable; Gradle will use its normal repositories.'
    exit 0
fi

if ! (cd "$artifact_dir" && printf '%s\n' \
    'd4918b9791ff87c4b7eb51beef56394d8b58e96a153318f978160d7807173173  nativeruntime-dist-compat-1.0.19.pom' \
    '33e0a0982b3ba599763af250b86eb8d67ab6c186c2fa2c5e823ee003bdcd4ed8  nativeruntime-dist-compat-1.0.19.jar' | sha256sum -c -); then
    rm -rf "$repository"
    echo '::error::Robolectric seed checksum verification failed.' >&2
    exit 1
fi

printf 'FOLD_CI_ROBOLECTRIC_REPOSITORY=%s\n' "$repository" >> "$GITHUB_ENV"
