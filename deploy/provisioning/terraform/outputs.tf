output "nodes" {
  description = "Provisioned VMs by node name."
  value = merge(
    { for k, m in module.optiplex_nodes : k => m.summary },
    { for k, m in module.laptop_nodes : k => m.summary },
  )
}

output "ansible_inventory_path" {
  description = "Generated inventory for deploy/configuration."
  value       = local_file.ansible_inventory.filename
}
