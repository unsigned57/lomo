# LAN Contract

Capability: pair trusted peers and transfer approved workspace items through `lomo-lan`.

- Given authenticated transcripts and explicit approval, When a batch is transferred, Then chunks, resume journals and per-item commit fences are verified.
- Given invalid frames, identities, signatures or workspace revisions, When they reach the protocol boundary, Then they are rejected without fallback transport.

Observable outcomes: deterministic frame/session tests and bounded recovery journals.
Excludes: Android NSD, multicast locks, Keystore private-key export and Compose.
