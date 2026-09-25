variable "name" {
  description = "Hyper-V VM name (also the file-name prefix for its disk and seed ISO)."
  type        = string
}

variable "hostname" {
  description = "Guest hostname; also the Kubernetes node name."
  type        = string
}

variable "role" {
  description = "\"server\" or \"agent\"; recorded in the VM notes."
  type        = string
}

variable "cpus" {
  type = number
}

variable "memory_mb" {
  type = number
}

variable "ipv4" {
  type = string
}

variable "mac" {
  description = "12 hex digits, Hyper-V format (no separators)."
  type        = string
}

variable "network" {
  type = object({
    prefix_length = number
    gateway       = string
    dns_servers   = list(string)
    search        = optional(list(string), [])
  })
}

variable "switch_name" {
  type = string
}

variable "vm_root" {
  description = "Existing directory on the Hyper-V host for this VM's files."
  type        = string
}

variable "base_image_path" {
  description = "Immutable golden VHDX on the Hyper-V host (parent of the differencing disk)."
  type        = string
}

variable "ssh_user" {
  type = string
}

variable "ssh_authorized_keys" {
  type = list(string)
}
