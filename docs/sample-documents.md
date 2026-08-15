# Verify the bundled Office samples

Each Provider example includes the same Word, Cell, and Show files from the
Thinkfree Office sample document set. They let you open, edit, save, close, and
reopen a real OOXML document immediately after cloning the repository.

| File | SHA-256 |
| --- | --- |
| `sample.docx` | `754464a613ba4d87d1535f69a9b1e3a0a8f84b8277d2bf4d0c74aacceceb23d7` |
| `sample.xlsx` | `967efb2432fda208586b56bca922103afb9b20c50136713793aabb3273059d8b` |
| `sample.pptx` | `2fb16c2c88b34ee88d0ef7cc2f09dfc9c60e8792a19c329d097dc3e3fe1ef78a` |

The files appear at these paths:

- Node.js: `storage/samples/`
- Java: `examples/java/storage/samples/`
- Python: `examples/python/storage/samples/`

The CI workflow checks that matching files have identical hashes and that all
nine copies contain valid OOXML ZIP structures.

Saving through Office intentionally changes the file and therefore its hash in
your local worktree. Restore a clean sample with Git before comparing it to the
table. Do not use a customer document as a repository fixture.
