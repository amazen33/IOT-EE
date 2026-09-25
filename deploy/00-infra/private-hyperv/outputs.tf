# Layer 0 -> Layer 1 contract. Layer 1 (deploy/configuration) reads these;
# nothing is copied by hand:
#   tofu output -raw ansible_inventory > ../../configuration/inventory/generated/hosts.yml

locals {
  control_plane = { for name, n in local.nodes : name => n if n.role == "control-plane" }
  workers       = { for name, n in local.nodes : name => n if n.role == "worker" }

  inventory = {
    all = {
      vars = {
        ansible_user = var.admin_user
      }
      children = {
        rke2_servers = {
          hosts = { for name, n in local.control_plane : name => { ansible_host = n.ipv4 } }
        }
        rke2_agents = {
          hosts = { for name, n in local.workers : name => { ansible_host = n.ipv4 } }
        }
      }
    }
  }
}

output "nodes" {
  description = "Every VM: role, IPv4, MAC, sizing, Hyper-V VM name."
  value = {
    for name, n in local.nodes : name => {
      vm_name      = hyperv_machine_instance.vm[name].name
      role         = n.role
      ipv4         = n.ipv4
      mac          = n.mac_upper
      cpus         = n.cpus
      memory_mb    = n.memory_mb
      data_disk_gb = n.data_disk_gb
    }
  }
}

output "nodes_json" {
  description = "Same as `nodes`, as a JSON string (tofu output -raw nodes_json)."
  value = jsonencode({
    for name, n in local.nodes : name => {
      role = n.role
      ipv4 = n.ipv4
      mac  = n.mac_upper
    }
  })
}

output "control_plane_ips" {
  description = "IPv4 addresses of the control-plane nodes."
  value       = [for n in values(local.control_plane) : n.ipv4]
}

output "worker_ips" {
  description = "IPv4 addresses of the worker nodes."
  value       = [for n in values(local.workers) : n.ipv4]
}

output "ansible_inventory" {
  description = "Ansible YAML inventory (groups rke2_servers / rke2_agents) for Layer 1."
  value       = yamlencode(local.inventory)
}

output "ansible_inventory_json" {
  description = "The same inventory as JSON (Ansible also accepts this as an inventory file)."
  value       = jsonencode(local.inventory)
}

output "network" {
  description = "The VM network, for Layer 1 settings such as the API VIP and LoadBalancer pool."
  value = {
    cidr        = var.network.cidr
    gateway     = var.network.gateway
    dns_servers = var.network.dns_servers
    switch_name = var.switch_name
  }
}
