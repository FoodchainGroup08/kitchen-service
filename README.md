# Kitchen Service

Real-time Kitchen Display System (KDS) for FoodChain. Kitchen staff use this service to monitor the live order queue and drive each order through its preparation lifecycle. All queue state lives in Redis — there is no database. Orders arrive automatically via Kafka events published by `order-service`, and every status change is broadcast to kitchen screens over WebSocket (STOMP).

**Base URL (via gateway):** `http://<gateway-host>/api`  
**Direct port:** `8084`  
**WebSocket endpoint:** `ws://<gateway-host>/ws-notifications` (STOMP)

---

## Authentication

All endpoints require a valid JWT:

```
Authorization: Bearer <token>
```

The gateway extracts `branchId` from the JWT claims and sets the `X-User-BranchId` header automatically. Kitchen staff do not need to pass `branchId` manually — it comes from their login token.

---

## How Orders Flow In

```
Customer places order
      ↓
order-service publishes Kafka event  →  kitchen-service consumes it
                                                ↓
                                    Order appears in RECEIVED queue
                                                ↓
                                    Kitchen staff accept → PREPARING
                                                ↓
                                    Kitchen staff mark ready → READY
                                                ↓
                                    Serve (dine-in) or Pickup (takeaway/delivery)
```

The kitchen never creates orders — it only reacts to them.

---

## All Endpoints at a Glance

| Method | Path | Description |
|--------|------|-------------|
| GET | `/v1/kitchen/queue` | Live queue (branchId from JWT) |
| GET | `/v1/kitchen/queue/{branchId}` | Live queue for a specific branch |
| POST | `/v1/kitchen/orders/{orderId}/accept` | Accept order → PREPARING |
| POST | `/v1/kitchen/orders/{orderId}/ready` | Mark prepared → READY |
| POST | `/v1/kitchen/orders/{orderId}/serve` | Complete dine-in order |
| POST | `/v1/kitchen/orders/{orderId}/pickup` | Complete takeaway/delivery |
| PATCH | `/v1/kitchen/orders/{orderId}/status` | Unified status update |

---

## Queue

### GET `/v1/kitchen/queue`

Returns the live queue for the authenticated user's branch. `branchId` is read from the `X-User-BranchId` header set by the gateway.

### GET `/v1/kitchen/queue/{branchId}`

Returns the live queue for a specific branch. Use this when the caller's token does not have a `branchId` claim (e.g. admin viewing any branch).

**Query params (both endpoints):**

| Param | Type | Default | Description |
|-------|------|---------|-------------|
| `page` | integer | `0` | Page within each status group |
| `size` | integer | `50` | Items per page within each group |

**Response `200`:**
```json
{
  "branchId": "00e03993-6425-4703-a38f-cc661ceedf44",
  "page": 0,
  "size": 50,
  "received": {
    "total": 12,
    "page": 0,
    "size": 50,
    "orders": [ { "...KitchenOrder..." } ]
  },
  "preparing": {
    "total": 5,
    "page": 0,
    "size": 50,
    "orders": [ { "...KitchenOrder..." } ]
  },
  "ready": {
    "total": 3,
    "page": 0,
    "size": 50,
    "orders": [ { "...KitchenOrder..." } ]
  }
}
```

**KitchenOrder object:**
```json
{
  "orderId": "b1c2d3e4-...",
  "id": "b1c2d3e4-...",
  "customerId": "customer-uuid",
  "customerName": "Omar Al-Hassan",
  "branchId": "00e03993-...",
  "orderType": "DINE_IN",
  "displayOrderType": "dine-in",
  "tableNumber": "T5",
  "notes": "No onions please",
  "totalAmount": 68.00,
  "status": "RECEIVED",
  "displayStatus": "received",
  "items": [
    {
      "menuItemId": "item-uuid",
      "id": "item-uuid",
      "menuItemName": "Kabsa Chicken",
      "name": "Kabsa Chicken",
      "quantity": 2,
      "specialInstructions": "Extra spicy"
    }
  ],
  "receivedAt": "2026-05-13T10:00:00",
  "acceptedAt": null,
  "readyAt": null
}
```

**Field notes:**

| Field | Notes |
|-------|-------|
| `orderId` / `id` | Same value — both exist for compatibility. Use either. |
| `status` | Internal uppercase: `"RECEIVED"`, `"PREPARING"`, `"READY"` |
| `displayStatus` | Frontend-friendly lowercase: `"received"`, `"preparing"`, `"ready"` |
| `orderType` | Internal: `"DINE_IN"`, `"TAKEAWAY"`, `"DELIVERY"` |
| `displayOrderType` | Frontend-friendly: `"dine-in"`, `"takeaway"`, `"delivery"` |
| `menuItemId` / `id` | Same value on items — both exist for compatibility. |
| `menuItemName` / `name` | Same value on items — both exist for compatibility. |
| `acceptedAt` | Set when kitchen accepts; `null` until then |
| `readyAt` | Set when kitchen marks ready; `null` until then |

---

## Kitchen Actions

All action endpoints accept an **optional** JSON body. If the body is omitted entirely the action still succeeds.

**Optional request body:**
```json
{
  "staffId": "staff-001",
  "notes": "Table 5 prefers well done"
}
```

---

### POST `/v1/kitchen/orders/{orderId}/accept`

Moves an order from **RECEIVED → PREPARING**. Also updates the order status in `order-service` (CONFIRMED → PREPARING).

**Response `200`:** updated `KitchenOrder` with `"status": "PREPARING"` and `"acceptedAt"` populated.

```json
{
  "orderId": "b1c2d3e4-...",
  "id": "b1c2d3e4-...",
  "status": "PREPARING",
  "displayStatus": "preparing",
  "acceptedAt": "2026-05-13T10:05:00",
  "readyAt": null
}
```

---

### POST `/v1/kitchen/orders/{orderId}/ready`

Moves an order from **PREPARING → READY**. Also updates the order status in `order-service` to READY.

**Response `200`:** updated `KitchenOrder` with `"status": "READY"` and `"readyAt"` populated.

```json
{
  "orderId": "b1c2d3e4-...",
  "status": "READY",
  "displayStatus": "ready",
  "acceptedAt": "2026-05-13T10:05:00",
  "readyAt": "2026-05-13T10:18:00"
}
```

---

### POST `/v1/kitchen/orders/{orderId}/serve`

Completes a **dine-in** order. Use this when food has been brought to the table. Removes the order from the kitchen queue and updates `order-service` to SERVED.

**Response `200`:** final `KitchenOrder` state (order is removed from queue after this call).

---

### POST `/v1/kitchen/orders/{orderId}/pickup`

Completes a **takeaway or delivery** order. Use this when the customer has collected or the rider has picked up. Removes the order from the queue and updates `order-service` to PICKED_UP.

**Response `200`:** final `KitchenOrder` state.

---

### PATCH `/v1/kitchen/orders/{orderId}/status`

Unified endpoint that accepts any valid target status. Use this if you want a single endpoint instead of the individual action endpoints.

**Request body:**
```json
{
  "newStatus": "PREPARING",
  "staffId": "staff-001",
  "notes": "Accepted by Ahmed"
}
```

| `newStatus` value | Effect |
|-------------------|--------|
| `"PREPARING"` | Same as `/accept` |
| `"READY"` | Same as `/ready` |
| `"SERVED"` | Same as `/serve` (dine-in) |
| `"PICKED_UP"` | Same as `/pickup` (takeaway/delivery) |

**Response `200`:** updated `KitchenOrder`.

---

## WebSocket — Real-Time Updates

Connect to the STOMP broker to receive live kitchen events without polling.

**Endpoint:** `ws://<gateway-host>/ws-notifications`  
**Subscribe topic:** `/topic/kitchen/{branchId}`

**Example (JavaScript with STOMP.js):**
```js
import { Client } from '@stomp/stompjs';

const client = new Client({
  brokerURL: 'ws://54.235.78.18:8080/ws-notifications',
  onConnect: () => {
    client.subscribe(`/topic/kitchen/${branchId}`, (msg) => {
      const event = JSON.parse(msg.body);
      console.log(event); // KitchenOrder or SLAAlert
    });
  },
});
client.activate();
```

---

## SLA Alerts

If an order has been waiting too long, the kitchen service automatically broadcasts an alert to the WebSocket topic.

**SLA thresholds:**
- **AMBER** — order waiting > 15 minutes
- **RED** — order waiting > 25 minutes

**Alert message shape:**
```json
{
  "orderId": "b1c2d3e4-...",
  "branchId": "00e03993-...",
  "severity": "AMBER",
  "minutesWaiting": 17,
  "message": "Order b1c2d3e4 has been waiting 17 minutes"
}
```

---

## Error Responses

```json
{
  "success": false,
  "status": 404,
  "message": "Order not found in queue: <orderId>",
  "error": "Not Found",
  "path": "/api/v1/kitchen/orders/.../accept",
  "timestamp": "2026-05-13T10:00:00Z"
}
```

| Status | When |
|--------|------|
| `400` | Bad request — missing branchId or invalid status transition |
| `401` | Missing or expired JWT |
| `404` | Order not found in the Redis queue |
| `500` | Unexpected server error |
