# Third-party notices

`ggwave-core/native/ggwave` contains a vendored and locally adapted snapshot of
[ggwave](https://github.com/ggerganov/ggwave), originally created by Georgi Gerganov.
ggwave is distributed under the MIT License.

The vendored sources also contain:

- Reed-Solomon implementation by Mike Lubinets, licensed under the MIT License.
  Its license is retained at `ggwave-core/native/ggwave/reed-solomon/LICENSE`.
- FFT routines carrying the Takuya Ooura copyright notice retained in
  `ggwave-core/native/ggwave/fft.h`.

The repository root `LICENSE` and the notices retained in the source tree must
be included in source and binary distributions.
