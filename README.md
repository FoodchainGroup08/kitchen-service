# kitchen-service

Spring Boot microservice for the FoodChain kitchen display system (KDS). Kitchen staff use this service to monitor the live order queue and drive each order through its preparation lifecycle. There is no relational database — queue state lives in Redis. Orders arrive via Kafka events published by `order-service`, and status changes are broadcast to kitchen displays in real time over WebSocket (STOMP/SockJS).

## Port and routing

| Item | Value |
|------|--------|
| **Port** | **8084** (`server.servlet.context-path` = `/api`) |
| **Direct base** | `http://localhost:8084/api` |
| **Via API Gateway** | `http://localhost:8080/api/v1/kitchen/...` (JWT validated at gateway; **`Authorization` is not forwarded** — use **`X-User-Id`** / **`X-User-BranchId`** / **`X-User-Role`** as injected by the gateway) |

REST controllers use the **`/v1/kitchen`** prefix on this service (full path on the box: `/api/v1/kitchen/...`).

---

## Queue Workflow

```
[Kafka: order.received]
        |
        v
    RECEIVED  ──── POST /v1/kitchen/orders/{id}/accept ────> PREPARING
    (new)          PATCH /v1/kitchen/orders/{id}/status           |
                         { "newStatus": "PREPARING" }          |
                                                    POST /v1/kitchen/orders/{id}/ready
                                                    PATCH /v1/kitchen/orders/{id}/status
                                                          { "newStatus": "READY" }
                                                               |
                                                               v
                                                           READY
                                                        /          \
                              POST .../serve            /            \  POST .../pickup
                              PATCH .../status         /              \ PATCH .../status
                               { "newStatus":         /                \ { "newStatus":
                                 "SERVED" }          v                  v  "PICKED_UP" }
                                               COMPLETED            COMPLETED
                                               (dine-in)         (takeaway/delivery)
```

COMPLETED orders are removed from Redis entirely; `order-service` is called over REST to persist the final status.

---

## Endpoints

All paths below are relative to **`/api`** on this host (`/api/v1/kitchen/...` full path).

### GET /v1/kitchen/queue

Frontend-friendly endpoint — resolves `branchId` from the gateway-injected JWT header or an explicit query param.

| Detail | Value |
|---|---|
| Priority | `?branchId=` query param first, then `X-User-BranchId` header |
| Auth | Through gateway: identity headers (`X-User-BranchId`, etc.). Direct calls may use Bearer JWT if configured. |

**Request headers / params**

| Name | Type | Required | Description |
|---|---|---|---|
| `X-User-BranchId` | header | conditional | Branch UUID set by the gateway from the JWT |
| `branchId` | query param | conditional | Overrides the header when both are present |

**Response 200**
```json
{
  "branchId": "uuid",
  "received":  [ { ...KitchenOrder } ],
  "preparing": [ { ...KitchenOrder } ],
  "ready":     [ { ...KitchenOrder } ]
}
```

**Response 400** — neither header nor query param provided.

---

### GET /v1/kitchen/queue/{branchId}

Legacy path-variable variant kept for backward compatibility.

| Detail | Value |
|---|---|
| Path variable | `branchId` — UUID of the branch |
| Auth | Same as `/v1/kitchen/queue` |

**Response 200** — same shape as above.

---

### PATCH /v1/kitchen/orders/{orderId}/status

Unified status-update endpoint consumed by the frontend.

**Request body**
```json
{
  "newStatus": "PREPARING | READY | SERVED | PICKED_UP",
  "staffId": "optional-staff-id",
  "notes": "optional notes"
}
```

| `newStatus` | Transition | Delegates to |
|---|---|---|
| `PREPARING` | RECEIVED -> PREPARING | `acceptOrder` |
| `READY` | PREPARING -> READY | `markReady` |
| `SERVED` | READY -> COMPLETED (dine-in) | `serveOrder` |
| `PICKED_UP` | READY -> COMPLETED (takeaway/delivery) | `pickupOrder` |

Values are matched case-insensitively.

**Responses**

| Code | Meaning |
|---|---|
| 200 | `KitchenOrder` with updated state |
| 400 | `newStatus` is not one of the four valid values |
| 404 | Order not found in the Redis queue |
| 409 | Invalid state transition (e.g. order is not in the expected status) |
| 502 | `order-service` call failed |

---

### POST /v1/kitchen/orders/{orderId}/accept

Moves an order from RECEIVED → PREPARING.

**Request body** (optional)
```json
{ "staffId": "...", "notes": "..." }
```

**Idempotency:** If the order is **already PREPARING**, this call succeeds with **200** and returns the current order (no duplicate transition / no duplicate call to order-service).

**Responses** — 200 KitchenOrder / 404 / 409 (invalid transition from current state) / 502

---

### POST /v1/kitchen/orders/{orderId}/ready

Moves an order from PREPARING -> READY.

**Responses** — 200 KitchenOrder / 404 / 409 / 502

---

### POST /v1/kitchen/orders/{orderId}/serve

Completes a dine-in order (READY -> COMPLETED). Removes from queue.

**Responses** — 200 KitchenOrder / 404 / 409 / 502

---

### POST /v1/kitchen/orders/{orderId}/pickup

Completes a takeaway or delivery order (READY -> COMPLETED). Removes from queue.

**Responses** — 200 KitchenOrder / 404 / 409 / 502

---

## KitchenOrder Shape

```typescript
{
  orderId: string;           // internal storage key
  id: string;                // alias for orderId (frontend-friendly)
  status: string;            // uppercase internally: RECEIVED | PREPARING | READY
  displayStatus: string;     // lowercase for frontend: "received" | "preparing" | "ready"
  orderType: string;         // uppercase internally: DINE_IN | TAKEAWAY | DELIVERY
  displayOrderType: string;  // hyphenated lowercase: "dine-in" | "takeaway" | "delivery"
  tableNumber?: string;
  customerName?: string;
  customerId: string;
  branchId: string;
  totalAmount: number;
  receivedAt: string;        // ISO-8601 LocalDateTime
  acceptedAt?: string;
  readyAt?: string;
  items: Array<{
    menuItemId: string;
    id: string;              // alias for menuItemId
    menuItemName: string;
    name: string;            // alias for menuItemName
    quantity: number;
    specialInstructions?: string;
  }>;
}
```

---

## Real-time updates (STOMP / SockJS)

The service registers a **SockJS** STOMP endpoint at **`/ws-kitchen`** (with servlet context `/api`, use **`http://localhost:8084/api/ws-kitchen`** or SockJS URL variant). It broadcasts to **`/topic/kitchen/{branchId}`** whenever an order in that branch changes.

```
SockJS endpoint : http://localhost:8084/api/ws-kitchen
Protocol        : STOMP over SockJS / WebSocket
Subscribe topic : /topic/kitchen/{branchId}
```

**Message payload**
```json
{ "orderId": "uuid", "status": "PREPARING" }
```

Connect from the browser with SockJS + STOMP (same pattern as Spring’s kitchen UI). If you expose this through a reverse proxy or gateway, align the **SockJS URL** with however `/api/ws-kitchen` is routed.

**Note:** Customer-facing raw WebSockets under **`/ws/kitchen/{branchId}`** are implemented by **notifications-service** and proxied from the gateway — see that service’s README for hook URLs.

---

## Redis Usage

No relational database. All state is ephemeral in Redis with a 24-hour TTL.

| Key pattern | Type | Content |
|---|---|---|
| `kitchen:order:{orderId}` | String (JSON) | Full `KitchenOrder` object |
| `kitchen:branch:{branchId}:received` | Sorted Set | Order IDs scored by `receivedAt` epoch ms |
| `kitchen:branch:{branchId}:preparing` | Sorted Set | Order IDs scored by `acceptedAt` epoch ms |
| `kitchen:branch:{branchId}:ready` | Sorted Set | Order IDs scored by `readyAt` epoch ms |

Orders are fetched from the sorted sets oldest-first (lowest score = earliest timestamp).

---

## Kafka Consumer Topics

| Topic | Event | Action |
|---|---|---|
| `order.received` | `OrderReceivedEvent` | Enqueues order into Redis as RECEIVED; broadcasts via WebSocket |

**Consumer group:** `kitchen-service-group`

---

## Environment Variables / Configuration

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATA_REDIS_HOST` | `localhost` | Redis hostname |
| `SPRING_DATA_REDIS_PORT` | `6379` | Redis port |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka broker(s) |
| `EUREKA_CLIENT_SERVICE_URL_DEFAULTZONE` | `http://localhost:8761/eureka/` | Eureka registry |
| `SPRING_CONFIG_IMPORT` | `optional:configserver:http://localhost:8888` | Spring Cloud Config Server |
| `SERVER_PORT` | `8084` | HTTP port |

Config is also loaded from the central **config-server** (`kitchen-service.yml` in `foodchain-config`).

---

## Running Locally

Prerequisites: Redis, Kafka, Eureka, and optionally the config-server must be running.

```bash
./mvnw spring-boot:run
```

Swagger UI is available at `http://localhost:8084/api/swagger-ui.html`.
