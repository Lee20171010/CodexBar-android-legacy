# Provider artwork

The bundled `app/src/main/res/drawable-nodpi/provider_*.png` assets identify the corresponding connected services. They are sourced from the providers below, retrieved on 2026-09-25. They are not app branding and do not imply endorsement. Marks remain the property of their respective owners and are not covered by this repository's code license.

The Codex icon is the Blossom **published by OpenAI for its official Codex IDE extension**. It is not presented as a separate Codex-only mark. Copilot uses the Copilot robot, not the GitHub Octocat. Kimi Code and Moonshot API currently publish the same Kimi favicon.

Artwork is bundled locally, with no runtime icon downloads. SVGs were rasterized with Sharp/librsvg; ICOs were decoded with Pillow. Only transparent outer canvas was trimmed, then the artwork was proportionally fitted into a transparent 256px square. Colors and geometry are preserved. IBM Bob CSS variables were resolved to the SVG's own default light palette before rasterizing, because the rasterizer does not resolve CSS custom properties. Compose displays the artwork without a tint, on a small white surface for reliable contrast in both themes.

The source SHA-256 values below identify the downloaded source files (or the named SVG extracted from a brand kit), before conversion.

| Service | Official source | Source SHA-256 |
| --- | --- | --- |
| Claude | [Anthropic press kit](https://www.anthropic.com/press-kit), `Claude logos/3 Claude Spark/SVG/Claude Spark - Clay.svg` | `6d53db4be375e899c937c26cf16684a80d6e869b1928d72b37748bef2560e219` |
| Codex | [Official OpenAI Codex IDE extension](https://marketplace.visualstudio.com/items?itemName=openai.chatgpt), [published icon](https://openai.gallerycdn.vsassets.io/extensions/openai/chatgpt/26.5917.62051/1790149959307/Microsoft.VisualStudio.Services.Icons.Default), version `26.5917.62051` | `2ce1c57dd3b312417106a487815ab1235bddce17e1fa9b32a2f048bd71ee25d3` |
| GitHub Copilot | [GitHub Primer Octicons, copilot-24](https://github.com/primer/octicons/blob/main/icons/copilot-24.svg) | `eeafb3c2f333e04ccf7d031ae215f7adafaed4c6352556b0bf79496e048bcdd7` |
| Gemini | [Google Gemini spark](https://www.gstatic.com/lamda/images/gemini_sparkle_4g_512_lt_f94943af3be039176192d.png), linked by gemini.google.com | `5e7cfecaa53f4f65a313fe89b0f389548126544a78fad8489510c70ae641a4a1` |
| Cursor | [Cursor brand kit](https://cursor.com/brand), `General Logos/Cube/SVG/CUBE_2D_LIGHT.svg` | `c483c02f78eb2619778fdd959e72a9adfac4844854472cd2653d4cbfd60e4d71` |
| z.ai | [Official logo](https://z-cdn.chatglm.cn/z-ai/static/logo.svg), linked by z.ai | `07a45e8e35b0b631ed2c68cd1cb041f9721b1ceeb0bd0e34f1459b0304a741c7` |
| ZenMux | [Official logo](https://cdn.marmot-cloud.com/storage/tbox-router/2025/08/18/mplTJpZ/big-logo.svg), linked by zenmux.ai | `184c27e3d7b0e63fe168e418a7fe2ecf4fb96e82fd963d59fd4833ec64c7ad7d` |
| Kimi Code | [Kimi product favicon](https://www.kimi.com/favicon.ico) | `df91c1ecf1d4894cf05845cdce44549ed3ccca3478b97a70972d1bc82ac4f2d1` |
| ElevenLabs | [Official brand guide](https://elevenlabs.io/brand), [symbol](https://11labs-nonprd-15f22c1d.s3.eu-west-3.amazonaws.com/a2ea339b-8b5e-41bb-b706-24eda8a4c9e3/elevenlabs-symbol.svg) | `84041b5ee800dd3cf5a4f731cda268bdaf5804577c2eba0f60e3aebd18edefb9` |
| OpenRouter | [Official brand guide](https://openrouter.ai/brand), [light glyph](https://openrouter.ai/brand/v2/openrouter-glyph-light.svg) | `f1be26f98d70ac8d5541c517905ef2dcac1b59d20f49ca8c175219e6cf52d5d1` |
| Synthetic | [Official product favicon](https://synthetic.new/favicon.svg) | `271da15c7a7596e5a491481ce6b3f3290749e302f87b03d1f0a91cceede7f8e9` |
| Chutes | [Official product favicon](https://chutes.ai/favicon.png) | `1ec9ffad11af70374baba4588c03938309a16130a12f91c635f8ff29d76bf165` |
| DeepSeek | [Official product favicon](https://www.deepseek.com/favicon.ico) | `30a4420e6e4dcb17fd7de560c5004346c2bc8cd971d2d5f5ee15326603e51321` |
| Venice | [Official product favicon](https://venice.ai/favicon.png) | `4c0bc911c1fd12ad2f79d01ad0ca60a8f1a85ed5aa0a2e41630fdc86f8b8cf83` |
| Moonshot API | [Official console favicon](https://platform.moonshot.ai/favicon.ico?v=3) | `df91c1ecf1d4894cf05845cdce44549ed3ccca3478b97a70972d1bc82ac4f2d1` |
| Cline | [Official brand guide](https://cline.bot/brand), [product icon](https://cline.bot/assets/branding/favicons/favicon-256x256.png) | `64087a72486aa5cfefe028e88dfdb9510b0cedaa4d946099a5cbabc4a2ec714a` |
| IBM Bob | [Official Bob product icon](https://bob.ibm.com/icon.svg?v=1) | `2d0239059a21146a37f2b1c365ae83724b564e078b78e1f57990537712ca01a6` |
| Fireworks AI | [Official product favicon](https://fireworks.ai/icon0.svg?e3d99deadffb6216) | `86b7f4b33ca48c5617f10e2cef5aaa7096c5e798090ceb2d0c21b01273660c76` |
| Devin | [Official product favicon](https://devin.ai/favicon.svg) | `fe0753d2e3823bc1eb8a37943234fac63733b8c9e8abff0ca0402a6c7ddcd682` |
| OpenCode Go | [Official favicon](https://opencode.ai/favicon.svg); background tiles dropped so the portal glyph keeps a transparent canvas | `e29bbe33380ad1c1ada9134b52f229d30e9776d60481512c9d81f2bb6f37def9` |
| Antigravity | Official Windows IDE onboarding asset `resources/app/out/media/jetski-logo-black.svg`; stable 1.107.0, commit `4603c2a412f8c7cca552ff00db91c3ee787016ff`. Original 112×112 path and `#202124` fill preserved in VectorDrawable. | `2154ea6962e12feb7be788539752d7bbe6491a8e9eecec86319dbb3292764867` |

GitHub Primer Octicons is MIT licensed; its notice is retained in [provider-icons-octicons-license.txt](provider-icons-octicons-license.txt). Other artwork follows its provider's brand terms, including [OpenAI](https://openai.com/brand/) and [OpenRouter](https://openrouter.ai/brand).
