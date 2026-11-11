#!/usr/bin/env bash
# build-templates-host.sh: builds the AOSP Templates Host (platform/packages/apps/Car/Templates,
# Host/, Apache-2.0), the open-source renderer Android Automotive uses for Car App Library apps,
# from a pinned commit, unmodified. Only build glue is applied, to a copy of the source:
#   - local.properties: the SDK path;
#   - settings.gradle: the path of android.car-system-stubs.jar (upstream points into an AOSP tree);
#   - the build.gradle files: the Car App Library release to build with (CAR_APP_VERSION below).
# Nothing in the host's Java/Kotlin sources, resources or manifests is changed.
#
#   build-templates-host.sh build [OUT.apk]      build the unsigned release APK (default
#                                                out/carhost/templates-host-unsigned.apk)
#   build-templates-host.sh sign KEYSTORE IN OUT sign IN with the ATL platform key, as AOSP builds
#                                                the host (certificate: platform); KEYSTORE is the
#                                                PKCS12 platform key of the ATL build
#                                                (build-aux/sign-framework-res.sh, alias "platform")
#
# Inputs, all pinned:
#   - the Templates repository at TEMPLATES_COMMIT (git, shallow fetch of that commit);
#   - android.car-system-stubs.jar for SDK 31 from platform/prebuilts/sdk at PREBUILTS_COMMIT,
#     checked against pins.sha256;
#   - Gradle GRADLE_VERSION (the version the host's wrapper names), checked against pins.sha256.
#     The host's gradlew and gradle-wrapper.jar are never run;
#   - JDK 17 (JAVA_HOME, else Homebrew openjdk@17), and an Android SDK with platforms;android-31, -33
#     and build-tools;30.0.3 (ANDROID_HOME/ANDROID_SDK_ROOT when they have them, else the
#     command-line tools are installed under the cache, checked against pins.sha256).
# A pin that is still empty in pins.sha256 stops the build and prints the hash to record.
#
# Cache: CARHOST_CACHE (default ~/.cache/teslalinux-carhost): sdk/, gradle-home/, downloads/.
# Work dir: CARHOST_WORK (default <repo>/out/carhost, git-ignored).
# The host APK is Apache-2.0 code built by us, so it can be shipped (release.yml builds it on an
# x86_64 Linux runner: Android's Linux aapt2 is x86_64 only).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
CACHE="${CARHOST_CACHE:-$HOME/.cache/teslalinux-carhost}"
WORK="${CARHOST_WORK:-$REPO/out/carhost}"
PINS="$HERE/pins.sha256"

TEMPLATES_REPO=https://android.googlesource.com/platform/packages/apps/Car/Templates
TEMPLATES_COMMIT=fb52ffb0be3e3f34c1631009e0b8199ed04f765f
PREBUILTS_REPO=https://android.googlesource.com/platform/prebuilts/sdk
PREBUILTS_COMMIT=3af7c93524be6f51e092b87b17f009f13ee98b43
STUBS_PATH=31/system/android.car-system-stubs.jar
GRADLE_VERSION=7.3.3
# The Car App Library the host is built with. Upstream pins 1.2.0-beta02, whose newest car API
# level is 4 (1.2.0 too); current apps require level 5 (Waze 5.25: minCarApiLevel 5), which the
# 1.3 line has (1.3.0-rc01, the 1.3 release on Google's Maven). Built with 1.3, the host gets
# patches/car-app-1.3.patch: the host-side methods 1.3's binder interfaces add (alerts, the
# microphone, app-driven refresh), each answering as a host without that feature, and compileSdk
# 33, which 1.3 requires. CARHOST_CAR_APP_VERSION=1.2.0-beta02 builds upstream exactly.
CAR_APP_VERSION="${CARHOST_CAR_APP_VERSION:-1.3.0-rc01}"
CMDLINE_TOOLS_BUILD=11076708
SDK_PACKAGES=("platforms;android-31" "platforms;android-33" "build-tools;30.0.3")

log() { printf '[carhost] %s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

sha256() {
	if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

# check FILE against the pin NAME in pins.sha256
check_pin() {
	local name=$1 file=$2 want got
	want=$(awk -v n="$name" '$2 == n { print $1 }' "$PINS")
	got=$(sha256 "$file")
	[ -n "$want" ] || die "no pin for $name in $PINS; after checking the download, record: $got $name"
	[ "$want" = "$got" ] || die "$name: sha256 $got does not match the pin $want"
}

fetch() { # URL OUT
	mkdir -p "$(dirname "$2")"
	[ -s "$2" ] && return 0
	log "downloading $1"
	curl -fsSL --retry 3 -o "$2.part" "$1"
	mv "$2.part" "$2"
}

find_java() {
	if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17\.'; then return; fi
	for j in /opt/homebrew/opt/openjdk@17 /usr/local/opt/openjdk@17 /usr/lib/jvm/java-17-openjdk-amd64 /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/temurin-17-jdk-amd64; do
		if [ -x "$j/bin/java" ]; then
			JAVA_HOME=$j
			[ -d "$j/libexec/openjdk.jdk/Contents/Home" ] && JAVA_HOME="$j/libexec/openjdk.jdk/Contents/Home"
			export JAVA_HOME
			return
		fi
	done
	die "JDK 17 not found (set JAVA_HOME)"
}

sdk_has_packages() { # SDK
	[ -f "$1/platforms/android-31/android.jar" ] && [ -f "$1/platforms/android-33/android.jar" ] && [ -x "$1/build-tools/30.0.3/zipalign" ]
}

find_sdk() {
	for s in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
		if [ -n "$s" ] && sdk_has_packages "$s"; then SDK=$s; return; fi
	done
	SDK="$CACHE/sdk"
	sdk_has_packages "$SDK" && return
	local os zip
	case "$(uname -s)" in
		Darwin) os=mac ;;
		Linux) os=linux ;;
		*) die "unsupported build OS $(uname -s)" ;;
	esac
	zip="$CACHE/downloads/commandlinetools-$os-${CMDLINE_TOOLS_BUILD}_latest.zip"
	fetch "https://dl.google.com/android/repository/commandlinetools-$os-${CMDLINE_TOOLS_BUILD}_latest.zip" "$zip"
	check_pin "commandlinetools-$os-${CMDLINE_TOOLS_BUILD}" "$zip"
	rm -rf "$SDK/cmdline-tools"
	mkdir -p "$SDK/cmdline-tools"
	unzip -q "$zip" -d "$SDK/cmdline-tools"
	mv "$SDK/cmdline-tools/cmdline-tools" "$SDK/cmdline-tools/latest"
	log "installing ${SDK_PACKAGES[*]} (sdkmanager checks each package's checksum)"
	# the SDK licenses are for whoever builds to accept: interactively, or CARHOST_ACCEPT_SDK_LICENSES=1
	# (CI, after reading them)
	if [ "${CARHOST_ACCEPT_SDK_LICENSES:-0}" = 1 ]; then
		yes | "$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses >/dev/null
	else
		"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" --licenses
	fi
	"$SDK/cmdline-tools/latest/bin/sdkmanager" --sdk_root="$SDK" "${SDK_PACKAGES[@]}" >/dev/null
	sdk_has_packages "$SDK" || die "SDK packages missing after install"
}

get_gradle() {
	local zip="$CACHE/downloads/gradle-$GRADLE_VERSION-bin.zip"
	GRADLE="$CACHE/gradle-$GRADLE_VERSION/bin/gradle"
	[ -x "$GRADLE" ] && return
	fetch "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" "$zip"
	check_pin "gradle-$GRADLE_VERSION-bin.zip" "$zip"
	unzip -q -o "$zip" -d "$CACHE"
}

get_source() { # -> $WORK/src/Host
	local git_dir="$CACHE/templates.git"
	if [ ! -d "$git_dir" ]; then
		git init -q --bare "$git_dir"
	fi
	if ! git --git-dir="$git_dir" cat-file -e "$TEMPLATES_COMMIT^{commit}" 2>/dev/null; then
		log "fetching Templates at $TEMPLATES_COMMIT"
		git --git-dir="$git_dir" fetch -q --depth 1 "$TEMPLATES_REPO" "$TEMPLATES_COMMIT"
	fi
	rm -rf "$WORK/src"
	mkdir -p "$WORK/src"
	git --git-dir="$git_dir" archive "$TEMPLATES_COMMIT" Host | tar -x -C "$WORK/src"
}

get_stubs() { # -> $STUBS
	STUBS="$CACHE/downloads/android.car-system-stubs-31.jar"
	if [ ! -s "$STUBS" ]; then
		# prebuilts/sdk is many GB: a blobless shallow clone of the pinned commit, then only this
		# one blob (git checks it against the commit)
		local git_dir="$CACHE/prebuilts-sdk.git"
		log "fetching $STUBS_PATH from prebuilts/sdk at $PREBUILTS_COMMIT"
		[ -d "$git_dir" ] || git init -q --bare "$git_dir"
		git --git-dir="$git_dir" fetch -q --depth 1 --filter=blob:none "$PREBUILTS_REPO" "$PREBUILTS_COMMIT"
		mkdir -p "$(dirname "$STUBS")"
		git --git-dir="$git_dir" cat-file blob "$PREBUILTS_COMMIT:$STUBS_PATH" > "$STUBS.part"
		mv "$STUBS.part" "$STUBS"
	fi
	check_pin android.car-system-stubs-31.jar "$STUBS"
}

# the build glue: paths only
glue() {
	local host="$WORK/src/Host"
	printf 'sdk.dir=%s\n' "$SDK" > "$host/local.properties"
	# upstream: rootDir.absolutePath + "../../../../../../prebuilts/sdk/31/system/<jar>" (an AOSP tree)
	sed -i.orig \
		-e "s|^gradle.ext.lib_car_system_stubs = .*|gradle.ext.lib_car_system_stubs = \"$STUBS\"|" \
		-e "s|^gradle.ext.lib_system_stubs = .*|gradle.ext.lib_system_stubs = \"$SDK/platforms/android-31/android.jar\"|" \
		"$host/settings.gradle"
	grep -q "lib_car_system_stubs = \"$STUBS\"" "$host/settings.gradle" || die "settings.gradle glue did not apply"
	rm -f "$host/settings.gradle.orig"
	# the Car App Library version (see CAR_APP_VERSION)
	local f
	for f in "$host/app/build.gradle" "$host/app/apphost/build.gradle" "$host/app/renderer/build.gradle"; do
		sed -i.orig -e "s|\"androidx.car.app:app:1.2.0-beta02\"|\"androidx.car.app:app:$CAR_APP_VERSION\"|" \
			-e "s|\"androidx.car.app:app-automotive:1.2.0-beta02\"|\"androidx.car.app:app-automotive:$CAR_APP_VERSION\"|" "$f"
		rm -f "$f.orig"
		grep -q "androidx.car.app:app:$CAR_APP_VERSION" "$f" || die "$f: the Car App Library version glue did not apply"
	done
	case "$CAR_APP_VERSION" in
		1.2.*) ;;
		1.3.*)
			for f in "$host/app/build.gradle" "$host/app/apphost/build.gradle" "$host/app/renderer/build.gradle"; do
				sed -i.orig -e 's|^\( *\)compileSdk 31$|\1compileSdk 33|' "$f"
				rm -f "$f.orig"
				grep -q "compileSdk 33" "$f" || die "$f: the compileSdk glue did not apply"
			done
			(cd "$WORK/src" && patch -p1 --forward --quiet < "$HERE/patches/car-app-1.3.patch") || die "patches/car-app-1.3.patch did not apply"
			;;
		*) die "no build glue for Car App Library $CAR_APP_VERSION" ;;
	esac
}

cmd_build() {
	local out="${1:-$WORK/templates-host-unsigned.apk}"
	mkdir -p "$CACHE" "$WORK"
	find_java
	find_sdk
	get_gradle
	get_source
	get_stubs
	glue
	log "building with Gradle $GRADLE_VERSION, JDK $JAVA_HOME, SDK $SDK"
	(
		cd "$WORK/src/Host"
		GRADLE_USER_HOME="$CACHE/gradle-home" "$GRADLE" --no-daemon --console=plain -q :app:assembleRelease
	)
	local apk="$WORK/src/Host/app/build/outputs/apk/release/app-release-unsigned.apk"
	[ -f "$apk" ] || die "no APK at $apk"
	mkdir -p "$(dirname "$out")"
	"$SDK/build-tools/30.0.3/zipalign" -f -p 4 "$apk" "$out"
	log "built $out (sha256 $(sha256 "$out"))"
}

# AOSP builds the host with the platform certificate; the signature is a JAR (v1) signature, which
# ATL's PackageParser reads for system packages (as for framework-res.apk)
cmd_sign() {
	[ $# -eq 3 ] || die "usage: $0 sign KEYSTORE IN.apk OUT.apk"
	local keystore=$1 in=$2 out=$3
	local pass=${ATL_PLATFORM_KEYSTORE_PASS:-android-translation-layer-platform}
	find_java
	[ -f "$keystore" ] || die "no platform keystore at $keystore"
	cp "$in" "$out.tmp"
	"$JAVA_HOME/bin/jarsigner" -keystore "$keystore" -storetype PKCS12 -storepass "$pass" \
		-sigalg SHA256withRSA -digestalg SHA-256 "$out.tmp" platform >/dev/null
	mv "$out.tmp" "$out"
	log "signed $out with $keystore"
}

case "${1:-}" in
	build) shift; cmd_build "$@" ;;
	sign) shift; cmd_sign "$@" ;;
	*) sed -n '2,30p' "$0"; exit 2 ;;
esac
