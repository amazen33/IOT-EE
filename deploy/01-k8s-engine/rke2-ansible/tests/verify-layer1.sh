#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export ANSIBLE_CONFIG="$PWD/ansible.cfg"

if git ls-files --error-unmatch inventory/hosts.ini >/dev/null 2>&1; then
  echo 'Real inventory/hosts.ini must not be tracked' >&2
  exit 1
fi

for playbook in validate-inventory.yml guard-cni.yml site-rke2.yml health.yml heal.yml tests/validate-inventory-live.yml; do
  ansible-playbook --syntax-check -i inventory/hosts.example.ini "$playbook" >/dev/null
done

ansible-playbook -i inventory/hosts.example.ini validate-inventory.yml >/dev/null
for inventory in tests/inventories/bad-*.ini; do
  if ansible-playbook -i "$inventory" validate-inventory.yml >/dev/null 2>&1; then
    echo "Invalid inventory was accepted: $inventory" >&2
    exit 1
  fi
done

if ansible-playbook -i inventory/hosts.example.ini tests/validate-inventory-live.yml >/dev/null 2>&1; then
  echo 'Documentation-only addresses were accepted for a live run' >&2
  exit 1
fi

if grep -rhoE '^\s+-? ?[a-z_]+\.[a-z_]+\.[a-z_]+:' . --include='*.yml' | grep -v 'ansible\.builtin\.'; then
  echo 'Non-builtin Ansible module detected' >&2
  exit 1
fi
echo 'Layer 1 offline checks passed.'
