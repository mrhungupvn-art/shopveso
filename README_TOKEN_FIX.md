# VESO Shop login fix

The VESO API wraps successful responses as:
`{ "ok": true, "message": "...", "data": { ... } }`

For `partner_login`, the access token is therefore at:
`data.token`
not at the top-level `token` field.

The Android client now:
- reads `data.token` and `data.partner`;
- sends `X-KCN-ID` on API requests;
- persists the returned KCN/store/category context;
- reads the common `data.*` wrapper for the existing product/order/draw screens.
