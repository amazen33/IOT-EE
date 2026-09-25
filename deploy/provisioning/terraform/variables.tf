variable "cluster_name" {
  description = "Prefix for VM names and the generated inventory."
  type        = string
  default     = "iotee-pc"
}

variable "hyperv_user" {
  description = "Windows account with Hyper-V Administrators rights on every host. Set TF_VAR_hyperv_user."
  type        = string
}

variable "hyperv_password" {
  description = "Password for hyperv_user. Set TF_VAR_hyperv_password; never commit it."
  type        = string
  sensitive   = true
}

variable "hosts" {
  description = <<-EOT
    Physical Hyper-V hosts. Keys must stay "optiplex" and "laptop" (they map to
    the provider aliases in providers.tf). vm_root is where each VM's disk and
    seed ISO are created; base_image_path is the immutable golden VHDX produced
    by hyperv-host/New-BaseImage.ps1; switch_name is the external vSwitch
    created by hyperv-host/Initialize-HyperVHost.ps1.
  EOT
  type = object({
    optiplex = object({
      address         = string
      winrm_port      = optional(number, 5986)
      winrm_insecure  = optional(bool, true)
      switch_name     = string
      vm_root         = string
      base_image_path = string
    })
    laptop = object({
      address         = string
      winrm_port      = optional(number, 5986)
      winrm_insecure  = optional(bool, true)
      switch_name     = string
      vm_root         = string
      base_image_path = string
    })
  })
}

variable "network" {
  description = "Static IPv4 settings shared by every node (cloud-init network-config v2)."
  type = object({
    prefix_length = number
    gateway       = string
    dns_servers   = list(string)
    search        = optional(list(string), [])
  })
}

variable "ssh_user" {
  description = "Admin user cloud-init creates on every node; Ansible connects as this user."
  type        = string
  default     = "iotee"
}

variable "ssh_authorized_keys" {
  description = "Public SSH keys for ssh_user. Public keys only; never a private key."
  type        = list(string)
}

variable "nodes" {
  description = <<-EOT
    Every cluster VM, keyed by a stable node name. SCALING IS DONE HERE:
    add an entry to add a node, remove one to remove it (after draining it,
    see deploy/README.md). A map (not count) keeps each node's identity, IP
    and MAC stable when others are added or removed.

    host       : "optiplex" or "laptop"
    role       : "server" (RKE2 control plane + etcd) or "agent" (worker)
    ipv4       : static address inside var.network
    mac        : static Hyper-V MAC, 12 hex digits, e.g. "00155D010A11"
    cpus, memory_mb : VM sizing (RKE2 server minimum: 2 vCPU, 4096 MB)
  EOT
  type = map(object({
    host      = string
    role      = string
    ipv4      = string
    mac       = string
    cpus      = number
    memory_mb = number
  }))

  validation {
    condition     = alltrue([for n in values(var.nodes) : contains(["optiplex", "laptop"], n.host)])
    error_message = "Each node's host must be \"optiplex\" or \"laptop\"."
  }
  validation {
    condition     = alltrue([for n in values(var.nodes) : contains(["server", "agent"], n.role)])
    error_message = "Each node's role must be \"server\" or \"agent\"."
  }
  validation {
    condition     = contains([1, 3, 5], length([for n in values(var.nodes) : n if n.role == "server"]))
    error_message = "Use 1, 3 or 5 server nodes: etcd needs an odd member count for quorum."
  }
  validation {
    condition     = alltrue([for n in values(var.nodes) : can(regex("^[0-9A-Fa-f]{12}$", n.mac))])
    error_message = "mac must be 12 hex digits with no separators (Hyper-V format)."
  }
  validation {
    condition     = length(distinct([for n in values(var.nodes) : n.ipv4])) == length(var.nodes)
    error_message = "Every node needs a unique ipv4."
  }
  validation {
    condition     = length(distinct([for n in values(var.nodes) : upper(n.mac)])) == length(var.nodes)
    error_message = "Every node needs a unique mac."
  }
  validation {
    condition     = alltrue([for n in values(var.nodes) : n.role != "server" || (n.cpus >= 2 && n.memory_mb >= 4096)])
    error_message = "Server nodes need at least 2 vCPUs and 4096 MB (RKE2 minimum)."
  }
}

variable "inventory_path" {
  description = "Where the generated Ansible inventory is written (git-ignored)."
  type        = string
  default     = "../../configuration/inventory/generated/hosts.yml"
}
