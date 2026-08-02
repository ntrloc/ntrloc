
<div align="center">

![NTRLOC logo](./artwork/Ntrloc-horizontal.png?raw=true)

| [![Maven CI with Test Report](https://github.com/ntrloc/ntrloc/actions/workflows/actions.yml/badge.svg)](https://github.com/ntrloc/ntrloc/actions/workflows/actions.yml)  | [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc) |                   [![Bugs](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=bugs)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc)                   |
| :---: |:-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------:|
| [![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc) | [![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=sqale_rating)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc) | [![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=security_rating)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc) |
| [![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc)      |        [![Coverage](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=coverage)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc)         | [![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=ntrloc_ntrloc&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=ntrloc_ntrloc) |

</div>

ntrloc is a graph-structured domain data platform: a schema-driven item/link/state-machine
model with a built-in admin UI, backed by Postgres.

### Run with Docker Compose

A [`docker-compose.yml`](./docker-compose.yml) at the repo root runs the app together with
its own Postgres instance:

```bash
docker compose up
```
