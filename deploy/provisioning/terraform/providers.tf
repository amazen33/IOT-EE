# One provider configuration per physical Hyper-V host. Terraform cannot
# for_each over providers, so each host gets an alias and its own module
# call in main.tf. Credentials come from TF_VAR_* environment variables
# (see terraform.tfvars.example); they are never written to Git or state.

provider "hyperv" {
  alias    = "optiplex"
  host     = var.hosts.optiplex.address
  port     = var.hosts.optiplex.winrm_port
  https    = true
  insecure = var.hosts.optiplex.winrm_insecure
  use_ntlm = true
  user     = var.hyperv_user
  password = var.hyperv_password
  timeout  = "120s"
}

provider "hyperv" {
  alias    = "laptop"
  host     = var.hosts.laptop.address
  port     = var.hosts.laptop.winrm_port
  https    = true
  insecure = var.hosts.laptop.winrm_insecure
  use_ntlm = true
  user     = var.hyperv_user
  password = var.hyperv_password
  timeout  = "120s"
}
