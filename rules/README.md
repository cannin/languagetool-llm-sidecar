# Adding an LLM rule

The sidecar loads every file ending in `.properties` in this directory when it starts. Files ending in `.example` are ignored.

1. Copy `example-rule.properties.example` to a descriptive `.properties` filename.
2. Copy `example-prompt.txt.example` to a descriptive `.txt` filename.
3. Set `promptFile` in the descriptor to the prompt filename.
4. Give the rule a unique uppercase ID ending in `_LLM`.
5. Replace the prompt's placeholder topic, positive examples, and ambiguity guidance.
6. Restart only the sidecar and send matching text through LT's `/v2/check` endpoint.

Descriptor fields:

| Field | Required | Meaning |
| --- | --- | --- |
| `id` | yes | Unique ID matching `[A-Z][A-Z0-9_]*_LLM` |
| `enabled` | no | `true` by default; set to `false` to skip the rule |
| `shortMessage` | yes | Compact message shown by LanguageTool clients |
| `description` | yes | Rule description returned by the LT API |
| `promptFile` | yes | UTF-8 prompt path relative to this descriptor |
| `categoryId` | no | Defaults to `LLM_POLICY` |
| `categoryName` | no | Defaults to `LLM policy` |

The sidecar sends category and style metadata in the native protocol. LanguageTool 6.9 currently exposes dynamically returned `GRPCRule` IDs under its fallback `MISC` category.

Every prompt must request the same JSON schema shown in the template. Cache keys include the rule ID and full prompt, so editing a prompt automatically causes fresh classifications.

For another directory, set `LT_LLM_RULES_DIRECTORY` or `llm.rulesDirectory`. An empty setting disables external rule discovery. Duplicate or malformed rule IDs stop startup with a clear error.

To disable several rules at deployment time without editing descriptors, set one comma-separated list, for example `LT_LLM_DISABLED_RULES=CATS_LLM,FLOWERS_LLM`. This setting applies uniformly as the rule count grows.
