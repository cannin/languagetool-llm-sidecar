# LanguageTool LLM sidecar

This project is a working demo of using a [LiteLLM](https://www.litellm.ai/) proxy to add semantic rules to LanguageTool: checks that are useful, but difficult to define with dictionaries, regular expressions, or traditional grammar patterns. The included examples detect direct and implied references to cats and flowers, and the same structure can be used for other policy-style rules.

The demo packages stock LanguageTool and a Java gRPC sidecar in one Docker image. LanguageTool sends text to the sidecar through its native remote-rule mechanism, and the sidecar calls the LiteLLM proxy. No LanguageTool core source is modified.

```text
Chrome -> LanguageTool HTTP :8081 -> LLM sidecar gRPC 127.0.0.1:50051 -> LiteLLM proxy
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

Set the LiteLLM connection values in the shell, then run the single container. The API base must be reachable from inside the container and normally ends in `/v1`:

```bash
export LT_LLM_API_BASE=https://litellm.example.com/v1
export LT_LLM_API_KEY=YOUR_LITELLM_API_KEY
export LT_LLM_MODEL=gpt-5

docker run --name languagetool-llm \
  --env LT_LLM_API_BASE \
  --env LT_LLM_API_KEY \
  --env LT_LLM_MODEL \
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

1. Create one application using the repository's `Dockerfile`.
2. Configure container port `8081` and route the desired HTTPS domain to it.
3. Set `LT_LLM_API_BASE`, `LT_LLM_API_KEY`, and `LT_LLM_MODEL` as Dokploy environment variables.
4. Optionally mount persistent storage at `/data` for the classification cache.
5. Optionally mount configuration files as described below.
6. Use `/v2/languages` as an HTTP health-check path on port 8081.

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
  --env LT_LLM_API_BASE \
  --env LT_LLM_API_KEY \
  --env LT_LLM_MODEL \
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

These are all environment variables added for the LiteLLM rule functionality. Non-empty values override the corresponding settings in `sidecar.properties`. The defaults shown are for the included Docker image.

| Variable | Docker default | Purpose |
| --- | --- | --- |
| `LT_LLM_API_BASE` | `http://127.0.0.1:4000/v1` | OpenAI-compatible LiteLLM proxy base URL. Set it to an address reachable from the container; `127.0.0.1` refers to the container itself. |
| `LT_LLM_API_KEY` | Not set | Bearer token sent to LiteLLM. Required when LLM checking is enabled and rules are loaded. Never commit this value or a populated `.env` file. |
| `LT_LLM_MODEL` | Not set | Required model or route name configured in the LiteLLM proxy, for example `gpt-5`. It is accepted only through the environment, not `sidecar.properties`. |
| `LT_LLM_ENABLED` | `true` | Enables or disables all LLM checks. Must be `true` or `false`. |
| `LT_LLM_DISABLED_RULES` | Empty | Comma-separated rule IDs to disable. |
| `LT_LLM_RULES_DIRECTORY` | `/config/rules` | Directory containing external rule descriptors and prompt files. |
| `LT_LLM_MINIMUM_SENTENCE_CHARACTERS` | `20` | Skips LLM requests for sentences shorter than this many characters. |
| `LT_LLM_REQUEST_TIMEOUT_SECONDS` | `30` | Timeout for each LiteLLM HTTP request. |
| `LT_LLM_MAX_CONCURRENT_REQUESTS` | `3` | Maximum number of simultaneous LiteLLM requests. |
| `LT_LLM_CACHE_DIRECTORY` | `/data/cache` | Writable directory for cached classifications. |
| `LT_LLM_CACHE_TTL_SECONDS` | `86400` | Lifetime of successful cached classifications. |
| `LT_LLM_ERROR_CACHE_TTL_SECONDS` | `30` | Lifetime of cached LiteLLM failures. |
| `LT_LLM_FAIL_OPEN` | `true` | When `true`, LiteLLM failures do not produce LanguageTool matches. Must be `true` or `false`. |

For example, a Dokploy deployment using an external LiteLLM proxy normally needs:

```text
LT_LLM_API_BASE=https://litellm.example.com/v1
LT_LLM_API_KEY=YOUR_LITELLM_API_KEY
LT_LLM_MODEL=gpt-5
```

Disable any number of rules without adding per-rule settings:

```text
LT_LLM_DISABLED_RULES=CATS_LLM,FLOWERS_LLM
```

## Add another LLM rule

No Java or LanguageTool changes are required.

Run these commands from the repository root to create a rule named `my-topic`:

```bash
cp rules/example-rule.properties.example rules/my-topic.properties
cp rules/example-prompt.txt.example rules/my-topic-prompt.txt
```

Edit `rules/my-topic.properties`. Give the rule a unique uppercase ID ending in `_LLM`, and set `promptFile` to the name of the prompt file in the same directory:

```properties
id = MY_TOPIC_LLM
promptFile = my-topic-prompt.txt
```

`promptFile` is resolved relative to the descriptor, so do not use the host repository path there. With the mount below, the files map as follows:

| Repository file on the Docker host | File inside the container |
| --- | --- |
| `rules/my-topic.properties` | `/config/rules/my-topic.properties` |
| `rules/my-topic-prompt.txt` | `/config/rules/my-topic-prompt.txt` |

Mount the complete host `rules/` directory read-only at the sidecar's default rules directory:

```bash
--mount type=bind,src="$(pwd)/rules",dst=/config/rules,readonly
```

Then restart the container. The sidecar loads every `/config/rules/*.properties` file at startup; files ending in `.example` are ignored.

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
