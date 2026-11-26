# DeepL Translate API Plugin for OmegaT 6.1.1+

A lightweight, zero-dependency Machine Translation plugin for **OmegaT 6.1.1+** using the official **DeepL API** (supports both **DeepL API Free** and **DeepL API Pro** plans).

## Features

* **Free & Pro Plan Auto-Detection:** Automatically routes requests to `api-free.deepl.com` (for keys ending with `:fx`) or `api.deepl.com` (for Pro keys).
* **Built-in Configuration Dialog:** Manage all settings directly inside OmegaT via **Options → Machine Translation → DeepL Translate API → Configure**.
* **Translation Model Selection (`model_type`):** Choose between `default`, `prefer_quality_optimized` (next-gen LLM), `quality_optimized`, and `latency_optimized`.
* **Smart Formality Control (`formality`):** Supports `default`, `more`, `less`, `prefer_more`, and `prefer_less`, automatically skipping the parameter for target languages where DeepL does not support formality (preventing HTTP `400` errors).
* **Context-Aware Translation (`context`):** Automatically sends surrounding segments (up to 2 preceding and 1 following segment) as context to improve translation accuracy without consuming your character quota.
* **Real-Time Quota Monitoring (`/v2/usage`):** Check your exact character usage and remaining limit on demand via the **Check Usage** button, or display the remaining character count directly in the Machine Translations pane.
* **Inline Tag Preservation:** Preserves OmegaT inline tags using `tag_handling=xml` and cleans up extra spaces around tags.
* **Rate-Limit Protection:** Built-in request pacing and automatic retry with exponential backoff to handle HTTP `429` (`Too Many Requests`) responses smoothly.

## Installation

1. Download `omegat-plugin-mt-DeepL-API-1.0.0.jar` from the [Releases](../../releases) page.
2. Copy the `.jar` file into your OmegaT user `plugins` folder:
   * **Linux / macOS:** `~/.omegat/plugins`
   * **Windows:** `%APPDATA%\OmegaT\plugins`
3. Restart OmegaT.

## Configuration

1. Open OmegaT and go to **Options → Machine Translation**.
2. Enable **DeepL Translate API** by checking the box next to it.
3. Click **Configure** (or double-click the entry), paste your DeepL API key, adjust your preferred options, and click **OK**.

## Building from Source

Requires **Java 11** or newer:

```bash
./gradlew clean jar
```

The compiled plugin archive will be generated in `build/libs/`.

## License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)** — see the [COPYING](COPYING) file for details.
