# Protocol fixtures

Copies of the language-neutral protocol cases the unit tests read: each schema file has `valid` (and some
`invalid`) examples, `keys.json` and `link-urls.json` are the app key and link card URL rules, and `scenarios/`
holds scripted exchanges the SDK must replay exactly. The server and every pingfront SDK run the same cases.
They are copied in for each release, so change them upstream rather than here. `keys.json` leaves out the cases
with secret-key-shaped values (the SDK checks public keys; `ProtocolTests` covers a pasted secret key).
