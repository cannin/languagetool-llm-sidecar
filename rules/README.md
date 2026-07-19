# Adding an LLM rule

The repository's `rules/` directory is mounted at `/config/rules/` inside the container. The sidecar loads every `/config/rules/*.properties` file when it starts. Files ending in `.example` are ignored.

From the repository root, create a descriptor and prompt with matching descriptive names:

```bash
cp rules/example-rule.properties.example rules/my-topic.properties
cp rules/example-prompt.txt.example rules/my-topic-prompt.txt
```

Then edit `rules/my-topic.properties`:

```properties
id = MY_TOPIC_LLM
promptFile = my-topic-prompt.txt
```

The `promptFile` value is resolved relative to the descriptor file. For this layout, use only the prompt filename—not the host path `rules/my-topic-prompt.txt`. This keeps the descriptor portable between the repository and `/config/rules` in Docker. After mounting the directory, the relationship is:

```text
rules/my-topic.properties          -> /config/rules/my-topic.properties
rules/my-topic-prompt.txt          -> /config/rules/my-topic-prompt.txt
                                      ^ loaded by promptFile = my-topic-prompt.txt
```

Replace the prompt's placeholder topic, positive examples, and ambiguity guidance. Mount the directory with `--mount type=bind,src="$(pwd)/rules",dst=/config/rules,readonly`, restart the container, and send matching text through LT's `/v2/check` endpoint.

Descriptor fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Unique ID matching `[A-Z][A-Z0-9_]*_LLM` |
| `enabled` | no | `true` by default; set to `false` to skip the rule |
| `shortMessage` | yes | Compact message shown by LanguageTool clients |
| `description` | yes | Rule description returned by the LT API |
| `promptFile` | yes | UTF-8 prompt filename or path relative to this descriptor's directory |
| `categoryId` | no | Defaults to `LLM_POLICY` |
| `categoryName` | no | Defaults to `LLM policy` |

The sidecar sends category and style metadata in the native protocol. LanguageTool 6.9 currently exposes dynamically returned `GRPCRule` IDs under its fallback `MISC` category.

Every prompt must request the same JSON schema shown in the template. Cache keys include the rule ID and full prompt, so editing a prompt automatically causes fresh classifications.

For another directory, set `LT_LLM_RULES_DIRECTORY` or `llm.rulesDirectory`. An empty setting disables external rule discovery. Duplicate or malformed rule IDs stop startup with a clear error.

To disable several rules at deployment time without editing descriptors, set one comma-separated list, for example `LT_LLM_DISABLED_RULES=CATS_LLM,FLOWERS_LLM`. This setting applies uniformly as the rule count grows.
