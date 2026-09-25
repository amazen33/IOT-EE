output "summary" {
  value = {
    vm_name  = hyperv_machine_instance.this.name
    hostname = var.hostname
    role     = var.role
    ipv4     = var.ipv4
    mac      = upper(var.mac)
  }
}
