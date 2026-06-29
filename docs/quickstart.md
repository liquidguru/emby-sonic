# Emby Sonic Quickstart

> **Private beta note:** the guided installer below pulls prebuilt images from
> GHCR, which are **not yet public**. Until launch, beta testers should build
> from source instead — see the Docker section in the
> [README](../README.md#deploy-on-a-nas-docker) (`docker compose up -d --build`).
> This guide applies as-is once the images are public.

Start by choosing the setup that matches where Docker will run.

| Scenario | Choose This When | Fast Path |
|---|---|---|
| NAS + Docker coordinator | Your Emby/NAS box should host the lightweight coordinator, and workers may run elsewhere. | Run `./install.sh`, choose `1`. |
| Single Linux/Windows Docker box | One machine will run the coordinator and a worker. | Run `./install.sh`, choose `2`. |
| Separate worker rig | The coordinator already exists, and this machine should only analyse tracks. | Run `./install.sh`, choose `3`. |

The guided installer writes `.env`, pulls the GHCR images, writes a generated
`docker-compose.installer.yml`, and runs `docker compose up -d`.

## Before You Start

You need:

- Docker with Compose v2 (`docker compose version`)
- An Emby server URL, for example `http://192.168.1.9:8096`
- An Emby API key from Emby Dashboard -> Advanced -> API Keys
- For GPU workers: NVIDIA drivers and NVIDIA Container Toolkit

Run the installer from the repo root:

```bash
chmod +x ./install.sh
./install.sh
```

On Windows, run it from Git Bash or WSL with Docker Desktop running.

## Scenario 1: NAS + Docker Coordinator

Use this when the NAS should run only the coordinator, or when you want a small
CPU worker on the NAS while a stronger worker can run elsewhere.

1. Run `./install.sh`.
2. Choose `1) NAS + Docker coordinator`.
3. Enter your Emby URL and API key.
4. Choose whether this NAS should also run a CPU worker.
5. Point the Emby plugin's Python Service URL at:

```text
http://<nas-host>:8765
```

Check status:

```bash
docker logs -f emby-sonic-coordinator
```

## Scenario 2: Single Linux/Windows Docker Box

Use this when one machine should run both the coordinator and the worker.

1. Run `./install.sh`.
2. Choose `2) Single Linux/Windows Docker box`.
3. Enter your Emby URL and API key.
4. If this machine has an NVIDIA GPU and Container Toolkit, answer yes to GPU.

Check the worker device:

```bash
docker logs -f emby-sonic-worker
```

Expected startup line:

```text
[worker docker-worker] device=cpu
```

With the GPU option enabled, expected startup line:

```text
[worker docker-worker] device=cuda
```

## Scenario 3: Separate Worker Rig

Use this when the coordinator is already running on another host and this machine
should only drain the analysis queue.

1. Run `./install.sh`.
2. Choose `3) Separate worker rig`.
3. Enter the coordinator URL, for example:

```text
http://192.168.1.9:8765
```

4. Enter the Emby URL and API key.
5. Answer yes to GPU if this machine has NVIDIA Container Toolkit installed.

The generated Compose file starts only the worker service.

## Manual GHCR Images

The installer uses these images:

```text
ghcr.io/liquidguru/emby-sonic-coordinator:latest
ghcr.io/liquidguru/emby-sonic-worker:latest
ghcr.io/liquidguru/emby-sonic-worker:cuda
```

For pinned releases, replace `latest` with a release tag such as `v0.1.0`.
CUDA workers also publish tag-specific variants such as `v0.1.0-cuda`.

## Updating

From the repo root:

```bash
docker compose --env-file .env -f docker-compose.installer.yml pull
docker compose --env-file .env -f docker-compose.installer.yml up -d
```

## Troubleshooting

If the worker logs `device=cpu` after choosing GPU:

```bash
docker run --rm --gpus all nvidia/cuda:12.4.1-base-ubuntu22.04 nvidia-smi
```

If that fails, fix the NVIDIA Container Toolkit install before retrying.

If the coordinator starts but app/plugin status fails, confirm the Emby URL is
reachable from inside Docker and that the API key is valid.
