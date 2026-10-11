# Historical structural regression fixtures

These UTF-8 JSON maps contain only entries consumed by the named structural freeze
tests. Keys are original project-relative paths; values decode to the original
UTF-8 bytes, including line endings and final newline. These are test data, never
compiled or loaded as runtime code. Missing fixtures or entries fail explicitly.

The original archive name was `starfold-<version>-source.zip`, with
entry prefix `starfold/`. SHA-256 below identifies the archive actually
read; it does not establish correspondence to a Git tag or a signed release.
Extraction verified a lossless UTF-8 round trip for every retained entry.

Exception: alpha.54-dev.3 GroundAir.java contains only the suffix starting with
`    public final Point eye`, exactly the portion compared by GroundProfileTest.
No other historical text was normalized. Existing allowances for GroundCover's
companion base material, open materials/palette saves, solar lighting and antipode
clipping remain unchanged. These guards explicitly prohibit unrelated structural
changes; replacing them with a few behavior examples would reduce coverage.
Existing behavior, NBT and wire tests remain in place.

Do not refresh from current sources just to make a failure pass. Review the
protected contract and intentional exceptions first. JSON keys provide the full
entry inventory; no unrelated archive entries were extracted.

| Consumer | Fixture | Entries | Bytes | Original archive SHA-256 |
| --- | --- | ---: | ---: | --- |
| GroundCosmosTest | 0.3.0-alpha.55-dev.2.json | 14 | 44136 | `123d08c2afdfab0ae311cd9428625fbed5b8a331b82f2312f2a95b9c8405e91d` |
| GroundFreeLookTest | 0.3.0-alpha.57-dev.2.json | 17 | 44297 | `1ea6bcc200fea7b56a1f4f7c0b7df86e44a6f965ef44caf83d3e757807b33bfc` |
| GroundGuidanceTest | 0.3.0-alpha.58-dev.1.json | 22 | 64173 | `fe6107f045a1df6cdb481c1aeaff21adda5969ba8dbab2ff428cd94b0742d312` |
| GroundOpenBlocksTest | 0.3.0-alpha.60-dev.1.json | 26 | 72562 | `a1764b580d7f8269b8c43734dc420fbc942f654f5058116dd91bd8137fb5392c` |
| GroundOrbitRuntimeTest | 0.3.0-alpha.54-dev.4.json | 12 | 41136 | `2a9c1b9361a39b715a157dbf6523f13b313b411a2fd04f29f47f0cc5961bf3c3` |
| GroundProfileTest | 0.3.0-alpha.54-dev.3.json | 9 | 29571 | `b382ddb7ba6d9ac2936bbdc0fd17abcb0d9fd414fd4fd592ab91a110aa510331` |
| GroundRevolutionTest | 0.3.0-alpha.56-dev.1.json | 18 | 62536 | `daf3b1fdfd696333a9bab8d4d1d7e14868d4e67e762957b4b9b2252b40d87b63` |
| GroundSolarTest | 0.3.0-alpha.59-dev.2.json | 25 | 79076 | `e4e81c416fea440182d7474d888cdebbd2bad4c96614d7c1dfa3cd591e5af475` |
| GroundSpinNavigationTest | 0.3.0-alpha.57-dev.4.json | 23 | 66253 | `dbf5b04937b04f4af07ba43e3a175d2ca9530cda191fc3c62f70b46b32d6bde0` |
| NativeExplorationTest | 0.3.0-alpha.68-dev.1.json | 1 | 1963 | `16de668c713abf51b8b331570828ee6223aabce6ac90a4c6729f2b31c12b58b5` |
| NativeGroundTest | 0.3.0-alpha.60-dev.2.json | 27 | 118505 | `f86b06fa7c2544a3149811a68e17a52a1857638d6af376a0e47aefb46c2b8a91` |

Fixture payload total: **624,208 bytes** (194 entries across 11 maps).
