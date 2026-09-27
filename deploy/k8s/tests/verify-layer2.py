#!/usr/bin/env python3
"""Offline Layer 2 check; run from any directory, including a copied package."""

from __future__ import annotations

import hashlib
import ipaddress
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[1]
UPSTREAM_SHA256 = "fac6c080d0916b8e7692a6c94a80fd1d7fbda07db8dc12dc187fd984d3c6ca9d"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def local_resources(kustomization: Path) -> list[Path]:
    document = yaml.safe_load(kustomization.read_text(encoding="utf-8"))
    require(document.get("kind") == "Kustomization", f"not a Kustomization: {kustomization}")
    files: list[Path] = []
    for name in document.get("resources", []):
        require("://" not in name, f"remote Kustomize resource: {name}")
        target = (kustomization.parent / name).resolve()
        require(target.is_relative_to(ROOT), f"resource escapes Layer 2: {name}")
        require(target.exists(), f"missing resource: {target}")
        if target.is_dir():
            files.extend(local_resources(target / "kustomization.yaml"))
        else:
            files.append(target)
    return files


def main() -> None:
    files = local_resources(ROOT / "kustomization.yaml")
    require(len(files) == 4, f"expected four local manifest files; found {len(files)}")
    vendored = ROOT / "kube-vip" / "kube-vip-cloud-controller.yaml"
    require(hashlib.sha256(vendored.read_bytes()).hexdigest() == UPSTREAM_SHA256,
            "vendored cloud-controller manifest differs from pinned upstream bytes")

    resources = {}
    for path in files:
        for doc in yaml.safe_load_all(path.read_text(encoding="utf-8")):
            if doc is None:
                continue
            metadata = doc.get("metadata", {})
            key = (doc.get("kind"), metadata.get("namespace", ""), metadata.get("name"))
            require(key not in resources, f"duplicate resource: {key}")
            resources[key] = doc

    provider = resources[("Deployment", "kube-system", "kube-vip-cloud-provider")]
    provider_image = provider["spec"]["template"]["spec"]["containers"][0]["image"]
    require(provider_image == "ghcr.io/kube-vip/kube-vip-cloud-provider:v0.0.12",
            "cloud-controller image pin changed without review")
    daemon = resources[("DaemonSet", "kube-system", "kube-vip-ds")]
    pod = daemon["spec"]["template"]["spec"]
    require(pod["hostNetwork"] is True, "ARP daemon needs host networking")
    container = pod["containers"][0]
    require(container["image"] == "ghcr.io/kube-vip/kube-vip:v1.2.4",
            "kube-vip image pin changed without review")
    env = {entry["name"]: entry["value"] for entry in container["env"]}
    require(env.get("cp_enable") == "false" and env.get("svc_enable") == "true",
            "Layer 2 must manage service VIPs only, never the API VIP")
    require(env.get("vip_arp") == "true" and env.get("vip_interface") == "eth0",
            "review the ARP mode and lab interface before changing them")

    pool = resources[("ConfigMap", "kube-system", "kubevip")]["data"]["range-global"]
    start, end = map(ipaddress.ip_address, pool.split("-", maxsplit=1))
    require(start.version == end.version == 4 and start <= end, "invalid IPv4 LoadBalancer pool")
    require(start in ipaddress.ip_network("10.20.0.0/24")
            and end in ipaddress.ip_network("10.20.0.0/24"),
            "lab LoadBalancer pool must remain within its documented network")
    print(f"Layer 2 offline checks passed: {len(files)} local files, {len(resources)} resources.")


if __name__ == "__main__":
    main()
