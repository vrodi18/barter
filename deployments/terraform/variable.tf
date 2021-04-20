variable "deployment_environment" {
  default = "dev"
}

variable "deployment_image" {
  default = "nginx:stable"
}

variable "deployment_endpoint" {
  type = "map"

  default = {
    dev   = "dev.barter"
    qa    = "qa.barter"
    prod  = "barter"
    stage = "stage.barter"
  }
}

variable "google_domain_name" {
  default = "fuchicorp.com"
}
