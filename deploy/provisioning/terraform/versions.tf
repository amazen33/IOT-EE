terraform {
  # OpenTofu >= 1.6 or Terraform >= 1.6. CI validates with OpenTofu.
  required_version = ">= 1.6.0"

  required_providers {
    # Community Hyper-V provider; drives each Windows host over WinRM.
    # 1.2.1 checked against the provider's GitHub releases (2026-09-25).
    hyperv = {
      source  = "taliesins/hyperv"
      version = "~> 1.2.1"
    }
    # Packs each node's cloud-init seed files into a zip for hyperv_iso_image.
    archive = {
      source  = "hashicorp/archive"
      version = ">= 2.4.0, < 3.0.0"
    }
    # Writes the generated Ansible inventory.
    local = {
      source  = "hashicorp/local"
      version = ">= 2.4.0, < 3.0.0"
    }
  }

  # State stays on the operator machine / self-hosted runner, never in Git
  # (*.tfstate* is ignored at the repository root). Move to a remote
  # backend with locking before more than one person runs apply.
  backend "local" {
    path = "terraform.tfstate"
  }
}
