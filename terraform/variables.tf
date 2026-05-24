variable "aws_region" {
  description = "AWS region to deploy into"
  type        = string
  default     = "us-east-1"
}

variable "prefix" {
  description = "Prefix for all resource names"
  type        = string
  default     = "neptune-lab"
}


