# teslalinux: shared host-side environment for the ATL host build scripts (hostbuild.sh,
# hostbuild-setup.sh, patch-core-oj.sh). Sourced, not run. Works on macOS (bash 3.2, BSD tools,
# Homebrew JDK), Linux, and Windows Git Bash (MSYS: cygpath, python.exe, ';' classpaths).
#
# Sets: REPO, WORK (ATL_WORK, default $REPO/out/atl-work), JARS, PY, CPSEP, IS_WINDOWS
# Defines: topath PATH, topath_list (stdin -> stdout), hostenv_die MSG, hostenv_use_jdk

REPO="$(cd "$HERE/../.." && pwd)"
# The work dir holds the inputs pulled from the guest (see hostbuild-setup.sh) and the build output.
# /out/ is git-ignored.
WORK="${ATL_WORK:-$REPO/out/atl-work}"
JARS="$WORK/atl-host/jars"

hostenv_die() { printf '\033[1;31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

IS_WINDOWS=0
case "$(uname -s)" in MSYS*|MINGW*|CYGWIN*) IS_WINDOWS=1 ;; esac
command -v cygpath >/dev/null 2>&1 && IS_WINDOWS=1

# A path in the form the host's native tools take: C:/... for Windows-native java/python under Git
# Bash, unchanged elsewhere.
if command -v cygpath >/dev/null 2>&1; then
  topath() { cygpath -m "$1"; }
  topath_list() { cygpath -m -f -; }
else
  topath() { printf '%s\n' "$1"; }
  topath_list() { cat; }
fi

# Java classpath separator of the JVM that will run (a Windows JVM wants ';' even under Git Bash).
if [[ $IS_WINDOWS -eq 1 ]]; then CPSEP=';'; else CPSEP=':'; fi

if command -v python3 >/dev/null 2>&1 && python3 -c 'import sys' >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  hostenv_die "no python3 or python on PATH"
fi

# Put a working JDK (java, javac, jar) first on PATH. On macOS, /usr/bin/javac is a launcher stub
# that only works when a JDK is registered with /usr/libexec/java_home, so "on PATH" is not enough:
# the candidate has to answer -version.
hostenv_jdk_ok() { [[ -x "$1/javac" && -x "$1/java" && -x "$1/jar" ]] && "$1/javac" -version >/dev/null 2>&1; }
hostenv_use_jdk() {
  local onpath
  onpath="$(command -v javac 2>/dev/null || true)"
  if [[ -n "$onpath" ]]; then
    # skip the macOS stub when no JDK is registered (calling it would only print "Unable to locate
    # a Java Runtime" and may pop up an install dialog)
    if ! { [[ "$(uname -s)" == Darwin && "$onpath" == /usr/bin/javac ]] && ! /usr/libexec/java_home >/dev/null 2>&1; }; then
      if javac -version >/dev/null 2>&1 && command -v jar >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
        return 0
      fi
    fi
  fi
  local cand
  for cand in ${JAVA_HOME:+"$JAVA_HOME/bin"} /opt/homebrew/opt/openjdk@17/bin /usr/local/opt/openjdk@17/bin; do
    case "$cand" in /opt/homebrew/*|/usr/local/opt/*) [[ "$(uname -s)" == Darwin ]] || continue ;; esac
    if hostenv_jdk_ok "$cand"; then
      PATH="$cand:$PATH"; export PATH
      return 0
    fi
  done
  hostenv_die "no working JDK: javac on PATH is missing or the macOS stub, \$JAVA_HOME/bin has none${JAVA_HOME:+ (JAVA_HOME=$JAVA_HOME)}, and /opt/homebrew/opt/openjdk@17/bin is not installed (brew install openjdk@17)"
}
