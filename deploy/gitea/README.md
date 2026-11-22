# Gitea Actions → Container Registry → Portainer → Docker host

## Three-server topology

- **Server A (Gitea):** central Git repository, Actions coordinator and Docker package registry.
- **Server B (Portainer):** management UI / API; it does **not** compile images.
- **Server C (Docker Engine):** runs the archive application's containers and persistent data.
- **Build runner:** a **separate, trusted** Linux VM is preferable. It may technically run on server A if resources permit, but never grant untrusted workflows Docker privileges on a machine that also stores production secrets.

The runner connects **outbound** to Gitea, retrieves the source and builds backend and frontend images. It pushes images to Gitea's Registry. The Docker daemon on **server C**, via Portainer, pulls the images from Registry. No CI credentials or code checkout are required on server C.

## 1. Preflight network and access

Confirm, without exposing credentials in screenshots:

1. Runner → Gitea HTTPS + Registry HTTPS: reachable (DNS, firewall, certificate trust).
2. Docker Engine host C → Registry HTTPS: reachable and trusts its internal CA.
3. Portainer B → Gitea HTTPS (only if managing the stack using a Git repository): reachable.
4. Portainer B ↔ Docker Engine C: the existing Portainer Agent / Docker endpoint already functions.
5. Internal registry uses a hostname trusted by the OS/Docker CA store; **do not** turn off TLS verification or mark an Internet-exposed registry as insecure.

Run on the **Docker Engine host C**:

```bash
docker volume ls
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker inspect <postgres-container> --format '{{json .Mounts}}'
docker inspect <minio-container> --format '{{json .Mounts}}'
```

Keep a verified backup of PostgreSQL **and** MinIO before upgrading. Save the original stack definition, env secrets, stable `SETTINGS_ENCRYPTION_KEY`, and `ARCHIVE_INSTANCE_UUID`. Do not move, rename or clear the existing volumes.

## 2. Configure Gitea and a dedicated runner

1. Enable Actions for the repository in Gitea (repository Settings → Actions).
2. On a dedicated Linux runner VM, install Docker Engine, Git 2.34.1+, Bash, Node.js for the checkout JavaScript action, and the matching Gitea `act_runner` release.
3. Obtain a **runner registration token** under Gitea's relevant admin/repository Actions → Runners settings; register a runner with label **`archive-builder:host`** (the workflow requests `archive-builder`). The host label runs jobs with the runner user's permissions; isolate this VM and do not use it for untrusted repositories or pull requests.
4. Keep Runner as a restricted system service and give Docker access only to the dedicated build identity. Docker daemon access is effectively root-equivalent on that host.
Example registration for the current `gitea-runner` CLI (use the compatible stable binary for your Gitea version). Execute on the **dedicated builder VM**, not on Portainer or the production Docker Engine:

```bash
# Place a Gitea-issued registration token in a private file (mode 600) first.
gitea-runner register --no-interactive \
  --instance https://gitea.example.internal/ \
  --token-file /etc/gitea-runner/registration-token \
  --name archive-builder-01 \
  --labels archive-builder:host

# Initial foreground smoke test; convert to a systemd service afterwards.
gitea-runner daemon
```

`gitea.example.internal` is a placeholder. Do not paste registration tokens into terminal command arguments, logs, or Git. The registered runner must show **online** in Gitea Actions settings. Use a dedicated builder identity and restrict branch permissions.

5. Gitea Actions `actions/checkout@v4` needs a reachable source for the action. In an offline network, configure Gitea's Actions default action URL or an approved internal mirror of checkout; **do not blindly add public Internet access**.
6. Configure protected `main` branch, PR review and CI gates; only trusted merges should trigger this privileged publish pipeline.

Runner-specific registration and service options differ between Gitea releases. Follow the documentation of the **installed Gitea/act_runner versions** before selecting a registration command. Runner service and Docker are not installed by this repository change.

## 3. Configure four Actions values

In Gitea repository **Settings → Actions → Variables**:

| Variable | Example (replace with actual internal values) |
| --- | --- |
| `ARCHIVE_REGISTRY_HOST` | `git.example.internal:3000` (no `https://`) |
| `ARCHIVE_IMAGE_OWNER` | `archive-team` (lowercase package owner) |

In Gitea repository **Settings → Actions → Secrets**:

| Secret | Purpose |
| --- | --- |
| `ARCHIVE_REGISTRY_USERNAME` | Gitea user/service account used for package push |
| `ARCHIVE_REGISTRY_TOKEN` | Personal access token with package write permission |

Use a **dedicated publishing account**, scope its token to package upload permissions and rotate it. Make sure the Gitea user can publish packages under `ARCHIVE_IMAGE_OWNER`. Do not put the token in a repository file, Dockerfile, Compose or logs.

## 4. Start the build

Files installed by this change:

- `.gitea/workflows/archive-images.yml`
- `deploy/gitea/build-and-push.sh`

**Only the internal Gitea repository executes Gitea Actions.** The external GitHub `main` does not automatically synchronize to Gitea. Pull the updated main into your authorized Gitea repository and confirm Dockerfiles are included.

Push to `main` or manually run the workflow from Gitea's Actions UI. The workflow:

1. Checks out the exact commit.
2. Builds `./backend/Archive/Archive` and `./frontend` using existing Dockerfiles.
3. Pushes two images tagged `sha-<full-40-char-commit-sha>`.
4. Prints the precise `ARCHIVE_BACKEND_IMAGE` / `ARCHIVE_FRONTEND_IMAGE` references for Portainer.

**It never connects to Portainer, starts/stops database containers, migrates stored data or modifies the live stack.** Dockerfiles build packages with their own build commands; this workflow does not replace the repository's separate CI test requirements.

Example result:

```text
ARCHIVE_BACKEND_IMAGE=git.example.internal:3000/archive-team/central-archive-backend:sha-0123456789abcdef0123456789abcdef01234567
ARCHIVE_FRONTEND_IMAGE=git.example.internal:3000/archive-team/central-archive-frontend:sha-0123456789abcdef0123456789abcdef01234567
```

These names are samples, not deployed image references.

## 5. Deploy carefully with Portainer on server B

1. In Portainer → **Registries**, add your actual Gitea registry with a **read-only** credential. The registry must be reachable **from server C**; having access only from server B is not enough.
2. On server C, verify the actual Docker volume names (not assumed based on the Portainer stack name), container mounts and Postgres major version.
3. **Update the existing stack**, do not create a new competing stack over the existing data.
4. Use `docker-compose.portainer.yml` from the exact version synchronized to internal Gitea, with original secrets and `ARCHIVE_POSTGRES_VOLUME`, `ARCHIVE_MINIO_VOLUME`, `ARCHIVE_BACKEND_VOLUME` set to the real volumes on **server C**.
5. Set `ARCHIVE_BACKEND_IMAGE` and `ARCHIVE_FRONTEND_IMAGE` to the exact **same commit** tag published by Actions.
6. For HTTPS frontend set `AUTH_COOKIE_SECURE=true`, and `ARCHIVE_PUBLIC_ORIGIN` to the true externally accessed origin. For HTTP-only testing, set secure cookies to false only inside a restricted environment.
7. Keep `ARCHIVE_BOOTSTRAP_ADMIN_ENABLED=false` and migration scheduling off for an existing installation. Take maintenance window for any Flyway updates.
8. Choose Portainer's **Pull image and redeploy**. Observe backend health, migration version, login, old projects and actual MinIO downloads before reopening writes.
9. If projects disappear, **stop writes** immediately and verify Postgres volume / DB configuration. If file metadata exists but downloads fail, inspect MinIO volume, bucket, object keys, and credentials.

The current Portainer Compose includes database and MinIO services. It is intended for *updating the same existing stack topology* with confirmed matching versions/volumes. If the actual live stack has separate Postgres/MinIO deployments or bind mounts, **adapt the Compose to reality first**; do not blindly replace the original stack.

## 6. Rollback

Keep the previously known-good image tags and a verified pre-deployment PostgreSQL + MinIO backup. Application rollback is only safe when the database schema remains backward-compatible. If an incompatible Flyway migration has run, use the documented controlled restoration procedure rather than blindly deploying an older application.

See `deploy/portainer/README.md` for existing-volume safeguards and `documents/DEPLOYMENT.md` for DB/S3 recovery details.
