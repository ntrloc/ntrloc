terraform {
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
  required_version = ">= 1.3.0"
}

provider "aws" {
  region = var.aws_region
}

# ---------------------------------------------------------------------------
# Data sources
# ---------------------------------------------------------------------------

data "aws_availability_zones" "available" {
  state = "available"
}

# ---------------------------------------------------------------------------
# VPC & Networking
# ---------------------------------------------------------------------------

resource "aws_vpc" "neptune" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = { Name = "${var.prefix}-vpc" }
}

resource "aws_internet_gateway" "neptune" {
  vpc_id = aws_vpc.neptune.id
  tags   = { Name = "${var.prefix}-igw" }
}

# Neptune subnet groups require at least 2 subnets, but they can both be in
# the same AZ — we just need 2 distinct subnet resources to satisfy the API.
resource "aws_subnet" "neptune" {
  count                   = 2
  vpc_id                  = aws_vpc.neptune.id
  cidr_block              = "10.0.${count.index}.0/24"
  availability_zone       = data.aws_availability_zones.available.names[count.index] # different AZs required by Neptune subnet group
  map_public_ip_on_launch = true

  tags = { Name = "${var.prefix}-subnet-${count.index}" }
}

resource "aws_route_table" "neptune" {
  vpc_id = aws_vpc.neptune.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.neptune.id
  }

  tags = { Name = "${var.prefix}-rt" }
}

resource "aws_route_table_association" "neptune" {
  count          = 2
  subnet_id      = aws_subnet.neptune[count.index].id
  route_table_id = aws_route_table.neptune.id
}

# ---------------------------------------------------------------------------
# Security Group — allow port 8182 from your IP only
# ---------------------------------------------------------------------------

resource "aws_security_group" "neptune" {
  name        = "${var.prefix}-sg"
  description = "Neptune access from local machine"
  vpc_id      = aws_vpc.neptune.id

  ingress {
    description = "Neptune port 8182 - open to all IPs, IAM auth enforced"
    from_port   = 8182
    to_port     = 8182
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "${var.prefix}-sg" }
}

# ---------------------------------------------------------------------------
# Neptune Cluster
# ---------------------------------------------------------------------------

resource "aws_neptune_subnet_group" "neptune" {
  name       = "${var.prefix}-subnet-group"
  subnet_ids = aws_subnet.neptune[*].id

  tags = { Name = "${var.prefix}-subnet-group" }
}

resource "aws_neptune_cluster_parameter_group" "neptune" {
  family      = "neptune1.4"
  name        = "${var.prefix}-cluster-params"
  description = "Neptune cluster parameter group"
}

resource "aws_neptune_cluster" "neptune" {
  cluster_identifier                   = "${var.prefix}-cluster"
  engine                               = "neptune"
  neptune_subnet_group_name            = aws_neptune_subnet_group.neptune.name
  vpc_security_group_ids               = [aws_security_group.neptune.id]
  neptune_cluster_parameter_group_name = aws_neptune_cluster_parameter_group.neptune.name
  skip_final_snapshot                  = true  # easy teardown
  apply_immediately                    = true
  iam_database_authentication_enabled  = true  # required for public accessibility

  tags = { Name = "${var.prefix}-cluster" }
}

resource "aws_neptune_cluster_instance" "neptune" {
  identifier         = "${var.prefix}-instance"
  cluster_identifier = aws_neptune_cluster.neptune.id
  instance_class     = "db.t3.medium"  # smallest available
  engine             = "neptune"
  apply_immediately  = true
  publicly_accessible = true

  tags = { Name = "${var.prefix}-instance" }
}
