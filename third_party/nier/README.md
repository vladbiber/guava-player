Nier Visualizer core, vendored from https://github.com/bogerchan/Nier-Visualizer
at 6af1e91 (2020-04-24), Apache-2.0 (see LICENSE). Only the `core` module sources.

Changes: Kotlin/SDK compile fixes. The app feeds it through `NVDataSource`
(our own PCM), so the `audiofx.Visualizer` path and RECORD_AUDIO are unused.
