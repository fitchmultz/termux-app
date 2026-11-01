#!/data/data/com.termux/files/usr/bin/bash
# Build the Fold lsof backport without modifying the installed prefix.
set -euo pipefail

if [[ $# -ne 3 ]]; then
    echo "Usage: bash $0 <lsof-4.99.7.tar.gz> <official-lsof.deb> <output.deb>" >&2
    exit 1
fi

source_archive="$(realpath "$1")"
official_deb="$(realpath "$2")"
output="$(realpath -m "$3")"
here="$(cd -- "$(dirname -- "$0")" && pwd)"
[[ "${PREFIX-}" == /data/data/com.termux/files/usr && "$(uname -m)" == aarch64 ]]
[[ ! -e "$output" ]]
printf '%s  %s\n' 4a10391aab0b8ce1f539e82a1966693b2a6cf225972a6504ebb7ec4fa71675de "$source_archive" | sha256sum -c -
printf '%s  %s\n' 6067a81467f8f2e63379fd3d1fc1c6eb6e115f9661d3802192b365a6ceb2fb72 "$official_deb" | sha256sum -c -

work="$(mktemp -d "${TMPDIR:-$PREFIX/tmp}/fold-lsof.XXXXXXXX")"
trap 'rm -rf -- "$work"' EXIT
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=1781618586
tar -xzf "$source_archive" -C "$work"
cd "$work/lsof-4.99.7"
patch -p1 < "$here/termux-main.patch"
patch -p1 < "$here/termux-misc.patch"
patch -p1 < "$here/eventpoll.patch"

export CC=clang
export CFLAGS='-fstack-protector-strong -Oz -D_FILE_OFFSET_BITS=64'
export CPPFLAGS="-I$PREFIX/include/tirpc"
export LDFLAGS="-L$PREFIX/lib -Wl,-rpath=$PREFIX/lib -Wl,--enable-new-dtags -Wl,--as-needed -Wl,-z,relro,-z,now -Wl,--no-as-needed,-landroid-support,--as-needed"
bash ./configure --host=aarch64-unknown-linux-android --prefix="$PREFIX" --with-libtirpc --with-selinux=no
# Configure records the device user and kernel in version output; identify the
# modified build without embedding private host details.
sed -i \
    -e 's/^#define LSOF_VERSION .*/#define LSOF_VERSION PACKAGE_VERSION " (Fold eventpoll fix)"/' \
    -e 's/^#define LSOF_HOST .*/#define LSOF_HOST "aarch64-unknown-linux-android"/' \
    -e 's/^#define LSOF_LOGNAME .*/#define LSOF_LOGNAME ""/' \
    -e 's/^#define LSOF_USER .*/#define LSOF_USER ""/' \
    -e 's/^#define LSOF_SYSINFO .*/#define LSOF_SYSINFO ""/' autotools/version.h
make -j2 lsof liblsof.la

package="$work/package"
dpkg-deb -R "$official_deb" "$package"
sed -i \
    -e 's/^Maintainer: .*/Maintainer: fitchmultz\/termux-app/' \
    -e 's/^Version: 4\.99\.7$/Version: 4.99.7-0fold1/' \
    -e 's/^Description: .*/Description: Lists open files for running Unix processes (Fold eventpoll fix)/' \
    "$package/DEBIAN/control"
payload="$package/data/data/com.termux/files/usr"
install -m 700 lsof "$payload/bin/lsof"
install -m 700 .libs/liblsof.so "$payload/lib/liblsof.so"
llvm-strip --strip-unneeded "$payload/bin/lsof" "$payload/lib/liblsof.so"
printf '%s\n' \
    'Termux Fold backport, derived from the official Termux lsof 4.99.7 package.' \
    'Includes upstream lsof commit e1f8076051c1adb02fd7c1a4c824e8f373a1ab7a.' \
    'https://github.com/fitchmultz/termux-app/tree/fold/main/tools/bootstrap/lsof' \
    > "$payload/share/doc/lsof/FOLD-BACKPORT"
chmod 600 "$payload/share/doc/lsof/FOLD-BACKPORT"
(cd "$package" && find data -type f -print0 | sort -z | xargs -0 md5sum > DEBIAN/md5sums)
dpkg-deb --root-owner-group --build "$package" "$output"
printf 'Built %s\n' "$output"
sha256sum "$output"
