#!/usr/bin/env bash

set -euo pipefail

usage() {
    echo "Usage: $0 --check" >&2
    echo "   or: JINGYI_CIG_PROTO_ROOT=/path/to/jingyi_cig/src/main/proto $0 --sync" >&2
    echo "Providing JINGYI_CIG_PROTO_ROOT with --check also verifies the upstream checkout." >&2
    exit 2
}

if [[ $# -ne 1 ]] || [[ "$1" != "--sync" && "$1" != "--check" ]]; then
    usage
fi

mode="$1"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
engine_root="$(cd "${script_dir}/../.." && pwd)"
target_proto_root="${engine_root}/src/main/proto"
lock_file="${engine_root}/proto-locks/cig_ca_proto.lock"
files=(
    "grpc/ca_service.proto"
    "grpc/ca.proto"
    "grpc/provider/beijing_ca.proto"
)

checksum() {
    shasum -a 256 "$1" | awk '{print $1}'
}

validate_lock_and_snapshot() {
    if [[ ! -f "${lock_file}" ]]; then
        echo "Missing lock: ${lock_file}" >&2
        exit 1
    fi

    local checksum_count
    checksum_count="$(awk 'NF == 2 && length($1) == 64 && $1 !~ /[^0-9a-f]/ { count++ } END { print count + 0 }' "${lock_file}")"
    if [[ "${checksum_count}" -ne "${#files[@]}" ]]; then
        echo "Lock must contain exactly ${#files[@]} vendored proto checksums" >&2
        exit 1
    fi

    local relative_path expected actual
    for relative_path in "${files[@]}"; do
        if [[ ! -f "${target_proto_root}/${relative_path}" ]]; then
            echo "Missing vendored proto: ${target_proto_root}/${relative_path}" >&2
            exit 1
        fi
        expected="$(awk -v path="${relative_path}" '$2 == path { print $1 }' "${lock_file}")"
        if [[ ${#expected} -ne 64 || "${expected}" =~ [^0-9a-f] ]]; then
            echo "Missing or invalid lock checksum for ${relative_path}" >&2
            exit 1
        fi
        actual="$(checksum "${target_proto_root}/${relative_path}")"
        if [[ "${actual}" != "${expected}" ]]; then
            echo "Vendored proto checksum mismatch: ${relative_path}" >&2
            exit 1
        fi
    done
}

resolve_source_checkout() {
    local configured_root="${JINGYI_CIG_PROTO_ROOT:-}"
    if [[ -z "${configured_root}" ]]; then
        echo "JINGYI_CIG_PROTO_ROOT is required for --sync" >&2
        exit 2
    fi
    source_proto_root="$(cd "${configured_root}" && pwd)"
    cig_root="$(git -C "${source_proto_root}" rev-parse --show-toplevel)"
    local expected_proto_root="${cig_root}/src/main/proto"
    if [[ "${source_proto_root}" != "${expected_proto_root}" ]]; then
        echo "JINGYI_CIG_PROTO_ROOT must point to ${expected_proto_root}" >&2
        exit 2
    fi

    local relative_path
    for relative_path in "${files[@]}"; do
        if [[ ! -f "${source_proto_root}/${relative_path}" ]]; then
            echo "Missing CIG proto: ${source_proto_root}/${relative_path}" >&2
            exit 1
        fi
    done
    if ! git -C "${cig_root}" diff --quiet -- "${files[@]/#/src\/main\/proto\/}" \
        || ! git -C "${cig_root}" diff --cached --quiet -- "${files[@]/#/src\/main\/proto\/}"; then
        echo "CIG CA proto files contain uncommitted changes; commit them before syncing" >&2
        exit 1
    fi
    source_commit="$(git -C "${cig_root}" rev-parse HEAD)"
}

validate_source_against_lock() {
    resolve_source_checkout
    local locked_commit
    locked_commit="$(sed -n 's/^source_commit=//p' "${lock_file}")"
    if [[ "${source_commit}" != "${locked_commit}" ]]; then
        echo "CIG checkout commit ${source_commit} does not match locked commit ${locked_commit}" >&2
        exit 1
    fi

    local relative_path
    for relative_path in "${files[@]}"; do
        if ! cmp -s "${source_proto_root}/${relative_path}" "${target_proto_root}/${relative_path}"; then
            echo "CIG source differs from vendored proto: ${relative_path}" >&2
            exit 1
        fi
    done
}

if [[ "${mode}" == "--check" ]]; then
    validate_lock_and_snapshot
    if [[ -n "${JINGYI_CIG_PROTO_ROOT:-}" ]]; then
        validate_source_against_lock
        echo "CIG CA proto snapshot and upstream checkout are up to date (${source_commit})"
    else
        locked_commit="$(sed -n 's/^source_commit=//p' "${lock_file}")"
        echo "CIG CA vendored proto snapshot matches lock (${locked_commit})"
    fi
    exit 0
fi

resolve_source_checkout
temp_lock="$(mktemp "${TMPDIR:-/tmp}/cig-ca-proto-lock.XXXXXX")"
cleanup() {
    if [[ -n "${temp_lock:-}" && -f "${temp_lock}" ]]; then
        rm -f "${temp_lock}"
    fi
}
trap cleanup EXIT

{
    echo "source_repository=jingyi_cig"
    echo "source_commit=${source_commit}"
    echo "source_root=src/main/proto"
    for relative_path in "${files[@]}"; do
        echo "$(checksum "${source_proto_root}/${relative_path}")  ${relative_path}"
    done
} > "${temp_lock}"

for relative_path in "${files[@]}"; do
    mkdir -p "$(dirname "${target_proto_root}/${relative_path}")"
    cp "${source_proto_root}/${relative_path}" "${target_proto_root}/${relative_path}"
done
mkdir -p "$(dirname "${lock_file}")"
cp "${temp_lock}" "${lock_file}"
echo "Synced CIG CA proto snapshot from ${source_commit}"
