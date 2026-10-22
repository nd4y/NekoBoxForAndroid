#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
source "$PROJECT_ROOT/buildScript/lib/core/get_source_env.sh"
WORKSPACE="$(dirname "$PROJECT_ROOT")"

# Never discard local development work while preparing a build.
checkout_source() {
  local name="$1" url="$2" commit="$3" path="$WORKSPACE/$1"
  if [[ ! -d "$path/.git" ]]; then
    git -c core.autocrlf=false clone --no-checkout "$url" "$path"
    git -C "$path" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$path" fetch origin "$commit"
    git -C "$path" -c core.autocrlf=false checkout --detach "$commit"
  fi
  if [[ "$(git -C "$path" rev-parse HEAD 2>/dev/null || true)" != "$commit" ]]; then
    if [[ -n "$(git -C "$path" status --porcelain)" ]]; then
      echo "Refusing to change revision of modified dependency: $path" >&2
      return 1
    fi
    git -C "$path" cat-file -e "$commit^{commit}" 2>/dev/null || git -C "$path" fetch origin "$commit"
    git -C "$path" -c core.autocrlf=false checkout --detach "$commit"
  fi
}

apply_patches() {
  local target="$1" directory="$2" patch normalized
  [[ -d "$directory" ]] || return 0
  for patch in "$directory"/*.patch; do
    [[ -f "$patch" ]] || continue
    normalized="$(mktemp)"
    # Git for Windows may have checked out patch files with CRLF.
    tr -d '\r' < "$patch" > "$normalized"
    if git -C "$WORKSPACE/$target" apply --reverse --check --recount --ignore-space-change -C0 "$normalized" >/dev/null 2>&1; then
      rm -f "$normalized"
      continue
    fi
    if ! git -C "$WORKSPACE/$target" apply --check --recount --ignore-space-change "$normalized"; then
      rm -f "$normalized"
      echo "Dependency patch does not apply: $patch" >&2
      return 1
    fi
    git -C "$WORKSPACE/$target" apply --recount --ignore-space-change "$normalized"
    rm -f "$normalized"
  done
}

checkout_source sing-box https://github.com/nd4y/sing-box.git "$COMMIT_SING_BOX"
checkout_source libneko https://github.com/matsuridayo/libneko.git "$COMMIT_LIBNEKO"
checkout_source amneziawg-go https://github.com/amnezia-vpn/amneziawg-go.git "$COMMIT_AMNEZIAWG"
checkout_source net https://github.com/golang/net.git "$COMMIT_X_NET"
checkout_source MasterDnsVPN-plus https://github.com/AlwaysBusyStack/MasterDnsVPN.git "$COMMIT_MASTERDNSVPN"
checkout_source utls https://github.com/AlwaysBusyStack/utls.git "$COMMIT_UTLS"
checkout_source byedpi https://github.com/hufrea/byedpi.git "$COMMIT_BYEDPI"
checkout_source netbird https://github.com/netbirdio/netbird.git "$COMMIT_NETBIRD"

apply_patches amneziawg-go "$WORKSPACE/sing-box/patches/amneziawg-go"
apply_patches net "$WORKSPACE/sing-box/patches/x-slash-net"
apply_patches byedpi "$PROJECT_ROOT/patches/byedpi"
apply_patches netbird "$PROJECT_ROOT/patches/netbird"
