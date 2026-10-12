---
name: Large CBR archive handling
description: Avoid heap growth when reading large local CBR/RAR files with Junrar.
---

For SAF-backed CBR/RAR files, stage the input to a temporary cache file with a fixed-size copy buffer and open Junrar with `Archive(File)`. Avoid `Archive(InputStream)` for large archives because its seekable stream adapter retains read blocks for random access. Do not add an arbitrary archive-size cap; staging requires enough temporary disk space.

**Why:** Large archives can crash from heap growth even though the application has no configured file-size limit.

**How to apply:** When changing local archive loading or Junrar versions, confirm the implementation stays file-backed and cleans up temporary archives after extraction.
