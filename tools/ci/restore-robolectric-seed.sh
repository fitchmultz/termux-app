#!/usr/bin/env bash
set -euo pipefail

repository="$(mktemp -d "$FOLD_CI_WORK/robolectric-repository.XXXXXXXX")"
trap 'rm -rf -- "$repository"' EXIT
base='https://github.com/fitchmultz/termux-app/releases/download/ci-robolectric-cache-1.0.19'
curl_args=(--disable --fail --silent --show-error --location --proto '=https' --proto-redir '=https' --retry 3)

fetch_verified() {
    local filename="$1" checksum="$2" directory="$3"
    mkdir -p "$directory" || exit 1
    if ! curl "${curl_args[@]}" "$base/$filename" --output "$directory/$filename"; then
        rm -f -- "$directory/$filename" || exit 1
        echo "::warning::$filename unavailable; normal dependency resolution remains enabled."
        return 1
    fi
    if ! (cd "$directory" && printf '%s  %s\n' "$checksum" "$filename" | sha256sum -c -); then
        echo "::error::$filename checksum verification failed." >&2
        exit 1
    fi
}

gradle_ready=false
gradle_dir="$repository/org/robolectric/nativeruntime-dist-compat/1.0.19"
if fetch_verified 'nativeruntime-dist-compat-1.0.19.pom' \
    'd4918b9791ff87c4b7eb51beef56394d8b58e96a153318f978160d7807173173' "$gradle_dir" &&
   fetch_verified 'nativeruntime-dist-compat-1.0.19.jar' \
    '33e0a0982b3ba599763af250b86eb8d67ab6c186c2fa2c5e823ee003bdcd4ed8' "$gradle_dir"; then
    gradle_ready=true
else
    rm -rf -- "$gradle_dir"
fi

runtime_ready=false
while read -r version checksum; do
    filename="android-all-instrumented-$version.jar"
    directory="$repository/org/robolectric/android-all-instrumented/$version"
    if fetch_verified "$filename" "$checksum" "$directory"; then
        runtime_ready=true
    fi
done <<'RUNTIME_SEEDS'
7.0.0_r1-robolectric-r1-i7 57dc8d57a5d69adacf3aa4306aefbf147bd1621da347e20f3cf1f41c88a5dd49
9-robolectric-4913185-2-i7 dbb1c88987b54cef3132695a1b4d2f4851ba06286a6fa7d15772f98fd1f0cdf7
17-robolectric-15733970-i7 30a15243ba9a6a016361876facc75432f4ec5db0f9e27d4984a4a6b167b432bd
RUNTIME_SEEDS

if [[ "$gradle_ready" == true ]]; then
    printf 'FOLD_CI_ROBOLECTRIC_REPOSITORY=%s\n' "$repository" >> "$GITHUB_ENV"
fi
if [[ "$runtime_ready" == true ]]; then
    printf 'FOLD_CI_ROBOLECTRIC_MAVEN_REPOSITORY=%s\n' "$repository" >> "$GITHUB_ENV"
fi
if [[ "$gradle_ready" == true || "$runtime_ready" == true ]]; then
    trap - EXIT
fi
