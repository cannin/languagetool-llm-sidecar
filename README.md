# LanguageTool LLM sidecar

This repository builds one Docker image containing stock LanguageTool and a Java gRPC sidecar. No LanguageTool core source is modified.

```text
Chrome -> LanguageTool HTTP :8081 -> LLM sidecar gRPC 127.0.0.1:50051 -> LLM API
```

The container exposes only port 8081. Its entrypoint starts both JVMs, forwards shutdown signals, and exits if either process fails so Docker or Dokploy can restart it.

The included rules are:

- `CATS_LLM`, which flags direct or implied cat references.
- `FLOWERS_LLM`, which flags direct or implied flower references.

## Docker quick start

Build the image:

```bash
docker build -t languagetool-llm .
```

Set the API key in the shell, then run the single container:

```bash
docker run --name languagetool-llm \
  --env LT_LLM_API_KEY \
  --publish 8081:8081 \
  --volume languagetool-llm-cache:/data \
  languagetool-llm
```

The API is available at `http://127.0.0.1:8081/v2`. Port 50051 is private inside the container.

The Docker build pins LanguageTool to commit `1e5a1a64f3b80072ccf221b221d02e50dc2c71d1`, matching the checked-in `ml_server.proto`. Override `LT_GIT_REF` only after confirming protocol compatibility:

```bash
docker build \
  --build-arg LT_GIT_REF=FULL_LANGUAGETOOL_COMMIT \
  -t languagetool-llm .
```

## Dokploy

1. Push this repository to a Git provider accessible to Dokploy.
2. Create one application using the repository's `Dockerfile`.
3. Configure container port `8081` and route the desired HTTPS domain to it.
4. Set `LT_LLM_API_KEY` as a Dokploy environment variable.
5. Optionally mount persistent storage at `/data` for the classification cache.
6. Optionally mount configuration files as described below.
7. Use `/v2/languages` as an HTTP health-check path on port 8081.

The [compose.example.yaml](compose.example.yaml) file demonstrates the same one-container deployment for local testing. Dokploy does not require Compose when deploying the Dockerfile directly.

## Mountable configuration

The image contains working defaults, so the cats and flowers rules require no mounts. These universal in-container paths can be replaced with Dokploy file or directory mounts:

| Container path | Repository example | Purpose |
| --- | --- | --- |
| `/config/languagetool.properties` | `languagetool-server.properties.example` | LT server configuration |
| `/config/remote-rules.json` | `remote-rules.json` | Connects LT to the gRPC sidecar |
| `/config/sidecar.properties` | `docker/sidecar.properties` | Sidecar host, port, rules, and cache paths |
| `/config/rules/` | `rules/` | Additional rule descriptors and prompts |
| `/data/cache/` | Persistent writable storage | LLM classification cache |

The container runs as non-root UID and GID `10001`. A bind-mounted `/data` directory must be writable by that identity; Docker named volumes inherit the image's prepared permissions automatically.

Mount an individual file read-only:

```bash
docker run --rm \
  --env LT_LLM_API_KEY \
  --publish 8081:8081 \
  --mount type=bind,src="$(pwd)/remote-rules.json",dst=/config/remote-rules.json,readonly \
  languagetool-llm
```

Alternatively, mount one complete directory at `/config:ro`. It must contain all three configuration files and a `rules/` directory:

```text
deployment-config/
├── languagetool.properties
├── remote-rules.json
├── sidecar.properties
└── rules/
```

Because both JVMs run in one container, `remote-rules.json` must connect to `127.0.0.1:50051`, as the included example does.

The default entrypoint paths can also be changed with:

- `LT_SERVER_CONFIG`
- `LT_SIDECAR_CONFIG`
- `LT_HTTP_PORT`
- `LT_ALLOW_ORIGIN`

## Environment settings

`LT_LLM_API_KEY` is required and must be supplied at runtime. Never commit it or a populated `.env` file.

Common optional overrides are:

- `LT_LLM_API_BASE`
- `LT_LLM_MODEL`
- `LT_LLM_ENABLED`
- `LT_LLM_DISABLED_RULES`
- `LT_LLM_RULES_DIRECTORY`
- `LT_LLM_CACHE_DIRECTORY`
- `LT_LLM_MAX_CONCURRENT_REQUESTS`
- `LT_LLM_REQUEST_TIMEOUT_SECONDS`

Disable any number of rules without adding per-rule settings:

```text
LT_LLM_DISABLED_RULES=CATS_LLM,FLOWERS_LLM
```

## Add another LLM rule

No Java or LanguageTool changes are required.

1. Copy `rules/example-rule.properties.example` to a descriptive `.properties` filename.
2. Copy `rules/example-prompt.txt.example` to a descriptive prompt filename.
3. Give the rule a unique uppercase ID ending in `_LLM`.
4. Set `promptFile` in the descriptor.
5. Mount the rules directory at `/config/rules:ro`.
6. Restart the container.

Set `enabled = false` in an external descriptor to prevent that rule's LLM calls. See [rules/README.md](rules/README.md) for the complete descriptor and prompt contract.

## Verify

Check LT and both included LLM rules through the public HTTP endpoint:

```bash
curl --get http://127.0.0.1:8081/v2/check \
  --data-urlencode language=en-US \
  --data-urlencode 'text=The tabby cat slept beside fresh roses.'
```

The response should contain `CATS_LLM` and `FLOWERS_LLM`.

For the Chrome extension, use **Local server** when running on the same computer. For Dokploy, select a custom server and enter:

```text
https://YOUR_LANGUAGETOOL_DOMAIN/v2
```

## Local Java development

Build and test the sidecar alone with Java 17:

```bash
mvn clean package
```

The runnable sidecar artifact is `target/languagetool-llm-sidecar.jar`. It can be started independently with:

```bash
java -jar target/languagetool-llm-sidecar.jar
```

An optional uncommitted `.env` file may provide `LT_LLM_API_KEY` for local development. The Docker and Dokploy deployments should use runtime environment variables instead.

## Publish the repository

After creating an empty remote repository:

```bash
git remote add origin YOUR_REPOSITORY_URL
git push -u origin main
```
