# VESO Shop API action fix

Fixed the Shop app to use the API actions that actually exist in the current VESO backend:

- `partner_food_list` for the VÉ tab
- `partner_pickups` / `partner_pending_pickups` for orders and alerts
- `partner_confirm_pickup` for order confirmation
- `partner_mark_ready` for ready-for-shipper
- `partner_food_create` (multipart) for adding a new ticket/listing

The previous app called `partner_products`, `partner_orders`, `partner_confirm`, and `partner_publish`; those actions are not present in the supplied VESO backend and caused `action không hợp lệ.` after login.
