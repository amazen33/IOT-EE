# ---------------------------------------------------------------------------
# Hyper-V host connection (WinRM over HTTPS; prepared by scripts/prep-hyperv-host.ps1)
# ---------------------------------------------------------------------------

variable "hyperv_host" {
  description = "Hyper-V host Terraform connects to. 127.0.0.1 when Terraform runs on the host itself."
  type = object({
    address  = string
    port     = optional(number, 5986)
    insecure = optional(bool, true) # the prep script issues a self-signed certificate
    timeout  = optional(string, "120s")
  })
  default = {
    address = "127.0.0.1"
  }
}

variable "hyperv_user" {
  description = "Windows account in the Hyper-V Administrators group. Set TF_VAR_hyperv_user; never commit it."
  type        = string
}

variable "hyperv_password" {
  description = "Password for hyperv_user. Set TF_VAR_hyperv_password; never commit it."
  type        = string
  sensitive   = true
}

# ---------------------------------------------------------------------------
# Host storage and template
# ---------------------------------------------------------------------------

variable "vm_root" {
  description = "Existing directory on the Hyper-V host for VM disks and seed ISOs (created by the prep script)."
  type        = string
}

variable "template_vhdx_path" {
  description = "Golden Ubuntu VHDX built by the prep script. Read-only parent of every OS disk; never modified."
  type        = string
}

variable "secure_boot_template" {
  description = "Gen-2 Secure Boot template. Linux guests need the Microsoft UEFI CA template."
  type        = string
  default     = "MicrosoftUEFICertificateAuthority"
}

# ---------------------------------------------------------------------------
# Network: private (Internal) switch + Windows NAT, one host
# ---------------------------------------------------------------------------

variable "switch_name" {
  description = "Internal vSwitch the VMs attach to (created by the prep script with the same name)."
  type        = string
  default     = "iotee-nat"
}

variable "network" {
  description = <<-EOT
    VM subnet behind the host's NAT. `gateway` is the host's vEthernet address
    on the switch (the prep script assigns it). Windows NAT has no DHCP server,
    so every VM gets a static address from `nodes[*].ip_host`.
  EOT
  type = object({
    cidr        = string
    gateway     = string
    dns_servers = list(string)
  })
  default = {
    cidr        = "10.20.0.0/24"
    gateway     = "10.20.0.1"
    dns_servers = ["1.1.1.1", "9.9.9.9"]
  }

  validation {
    condition     = can(cidrhost(var.network.cidr, 0))
    error_message = "network.cidr must be a valid IPv4 CIDR, e.g. 10.20.0.0/24."
  }
  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+$", var.network.gateway))
    error_message = "network.gateway must be an IPv4 address."
  }
  validation {
    condition     = !can(cidrhost(var.network.cidr, 0)) || (can(cidrhost("${var.network.gateway}/${split("/", var.network.cidr)[1]}", 0)) && try(cidrhost("${var.network.gateway}/${split("/", var.network.cidr)[1]}", 0) == cidrhost(var.network.cidr, 0), false))
    error_message = "network.gateway must be inside network.cidr."
  }
  validation {
    condition     = length(var.network.dns_servers) > 0
    error_message = "Give at least one DNS server (Windows NAT does not provide DNS)."
  }
}

# ---------------------------------------------------------------------------
# Guest access for Layer 1: exactly one SSH public key, nothing else
# ---------------------------------------------------------------------------

variable "admin_user" {
  description = "Login cloud-init creates on every VM. Layer 1 (Ansible) connects as this user."
  type        = string
  default     = "iotee"
}

variable "ssh_public_key_path" {
  description = "Path to the operator's SSH PUBLIC key file (never a private key)."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"

  validation {
    condition     = fileexists(pathexpand(var.ssh_public_key_path))
    error_message = "No SSH public key at ssh_public_key_path. Create one with: ssh-keygen -t ed25519"
  }
  validation {
    condition     = !fileexists(pathexpand(var.ssh_public_key_path)) || can(regex("^(ssh-ed25519|ssh-rsa|ecdsa-sha2-nistp[0-9]+) [A-Za-z0-9+/=]+", trimspace(file(pathexpand(var.ssh_public_key_path)))))
    error_message = "ssh_public_key_path must point at a PUBLIC key (a line starting with ssh-ed25519, ssh-rsa or ecdsa-sha2-...), never a private key."
  }
}

# ---------------------------------------------------------------------------
# VMs
# ---------------------------------------------------------------------------

variable "nodes" {
  description = <<-EOT
    Every VM, keyed by hostname (also the Kubernetes node name).
      role         : "control-plane" or "worker"
      ip_host      : host number inside network.cidr (10 -> 10.20.0.10)
      mac          : static Hyper-V MAC, 12 hex digits, no separators
      cpus         : virtual processors
      memory_mb    : static RAM
      data_disk_gb : size of the empty second disk (Layer 1 formats and mounts it)
    A map, not a count: adding or removing one VM never renumbers the others.
  EOT
  type = map(object({
    role         = string
    ip_host      = number
    mac          = string
    cpus         = number
    memory_mb    = number
    data_disk_gb = number
  }))
  default = {
    "rke2-master-01" = { role = "control-plane", ip_host = 10, mac = "00155D140A10", cpus = 2, memory_mb = 4096, data_disk_gb = 40 }
    "rke2-worker-01" = { role = "worker", ip_host = 21, mac = "00155D140A21", cpus = 2, memory_mb = 4096, data_disk_gb = 60 }
    "rke2-worker-02" = { role = "worker", ip_host = 22, mac = "00155D140A22", cpus = 2, memory_mb = 4096, data_disk_gb = 60 }
  }

  validation {
    condition     = alltrue([for n in values(var.nodes) : contains(["control-plane", "worker"], n.role)])
    error_message = "Each node's role must be \"control-plane\" or \"worker\"."
  }
  validation {
    condition     = contains([1, 3, 5], length([for n in values(var.nodes) : n if n.role == "control-plane"]))
    error_message = "Use 1, 3 or 5 control-plane nodes (etcd needs an odd member count)."
  }
  validation {
    condition     = alltrue([for n in values(var.nodes) : can(regex("^[0-9A-Fa-f]{12}$", n.mac))])
    error_message = "mac must be 12 hex digits with no separators (Hyper-V format), e.g. 00155D140A10."
  }
  validation {
    condition     = length(distinct([for n in values(var.nodes) : upper(n.mac)])) == length(var.nodes)
    error_message = "Every node needs a unique mac."
  }
  validation {
    condition     = length(distinct([for n in values(var.nodes) : n.ip_host])) == length(var.nodes)
    error_message = "Every node needs a unique ip_host."
  }
  validation {
    condition     = alltrue([for n in values(var.nodes) : n.ip_host >= 2 && n.ip_host <= 254])
    error_message = "ip_host must be between 2 and 254 (1 is conventionally the gateway)."
  }
  validation {
    condition     = alltrue([for n in values(var.nodes) : n.cpus >= 1 && n.memory_mb >= 2048 && n.data_disk_gb >= 10])
    error_message = "Each node needs at least 1 vCPU, 2048 MB RAM and a 10 GB data disk."
  }
  validation {
    condition     = alltrue([for n in values(var.nodes) : n.role != "control-plane" || (n.cpus >= 2 && n.memory_mb >= 4096)])
    error_message = "Control-plane nodes need at least 2 vCPUs and 4096 MB (RKE2 server minimum)."
  }
  validation {
    condition     = alltrue([for name in keys(var.nodes) : can(regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", name))])
    error_message = "Node names must be valid DNS labels (lowercase letters, digits, hyphens)."
  }
}
