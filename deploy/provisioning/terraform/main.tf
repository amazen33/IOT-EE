locals {
  nodes_by_host = {
    for host in ["optiplex", "laptop"] :
    host => { for name, n in var.nodes : name => n if n.host == host }
  }
}

module "optiplex_nodes" {
  source   = "./modules/hyperv-node"
  for_each = local.nodes_by_host.optiplex
  providers = {
    hyperv = hyperv.optiplex
  }

  name                = "${var.cluster_name}-${each.key}"
  hostname            = each.key
  role                = each.value.role
  cpus                = each.value.cpus
  memory_mb           = each.value.memory_mb
  ipv4                = each.value.ipv4
  mac                 = each.value.mac
  network             = var.network
  switch_name         = var.hosts.optiplex.switch_name
  vm_root             = var.hosts.optiplex.vm_root
  base_image_path     = var.hosts.optiplex.base_image_path
  ssh_user            = var.ssh_user
  ssh_authorized_keys = var.ssh_authorized_keys
}

module "laptop_nodes" {
  source   = "./modules/hyperv-node"
  for_each = local.nodes_by_host.laptop
  providers = {
    hyperv = hyperv.laptop
  }

  name                = "${var.cluster_name}-${each.key}"
  hostname            = each.key
  role                = each.value.role
  cpus                = each.value.cpus
  memory_mb           = each.value.memory_mb
  ipv4                = each.value.ipv4
  mac                 = each.value.mac
  network             = var.network
  switch_name         = var.hosts.laptop.switch_name
  vm_root             = var.hosts.laptop.vm_root
  base_image_path     = var.hosts.laptop.base_image_path
  ssh_user            = var.ssh_user
  ssh_authorized_keys = var.ssh_authorized_keys
}

# Ansible inventory generated from the same node map, so the two layers can
# never disagree about which machines exist. Written outside Git.
resource "local_file" "ansible_inventory" {
  filename        = var.inventory_path
  file_permission = "0644"
  content = templatefile("${path.module}/templates/inventory.yml.tftpl", {
    cluster_name = var.cluster_name
    ssh_user     = var.ssh_user
    servers      = { for name, n in var.nodes : name => n if n.role == "server" }
    agents       = { for name, n in var.nodes : name => n if n.role == "agent" }
  })
}
