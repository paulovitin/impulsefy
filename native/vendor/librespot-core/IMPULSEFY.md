# Local compatibility patch

Source: librespot-core 0.8.0, crates.io (MIT), matching the existing dependency.
Only `src/spclient.rs` differs from the published source: when Android/iOS uses a
desktop/device OAuth client ID, request the client token with that same desktop
identity/version and Linux protocol data. Keep the upstream mobile flow for
existing Android Spotify Connect credentials. This fixes mismatched client IDs in
login5, which otherwise returns BAD_REQUEST for the device authorization grant.

Remove this override when an upstream release supports the same combination.
