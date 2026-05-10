# kitchen-service

Spring Boot microservice for the FoodChain kitchen display system (KDS). Kitchen staff use this service to monitor the live order queue and drive each order through its preparation lifecycle. There is no database — all queue state lives in Redis. Orders arrive via Kafka events published by `order-service`, and every status change is broadcast to kitchen displays in real time over WebSocket (STOMP).

## Port

`8084` (context path `/api`). Full base URL: `http://localhost:8084/api`

---

## Queue Workflow

```
[Kafka: order.received]
        |
        v
    RECEIVED  ──── POST /kitchen/orders/{id}/accept ────> PREPARING
    (new)          PATCH /kitchen/orders/{id}/status           |
                         { "newStatus": "PREPARING" }          |
                                                    POST /kitchen/orders/{id}/ready
                                                    PATCH /kitchen/orders/{id}/status
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

All paths are relative to the context root `/api`. The service is registered with Eureka as `kitchen-service` and is reachable through the API gateway.

### GET /kitchen/queue

Frontend-friendly endpoint — resolves `branchId` from the gateway-injected JWT header or an explicit query param.

| Detail | Value |
|---|---|
| Priority | `?branchId=` query param first, then `X-User-BranchId` header |
| Auth | Bearer JWT (forwarded by gateway) |

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

### GET /kitchen/queue/{branchId}

Legacy path-variable variant kept for backward compatibility.

| Detail | Value |
|---|---|
| Path variable | `branchId` — UUID of the branch |
| Auth | Bearer JWT |

**Response 200** — same shape as above.

---

### PATCH /kitchen/orders/{orderId}/status

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

### POST /kitchen/orders/{orderId}/accept

Moves an order from RECEIVED -> PREPARING.

**Request body** (optional)
```json
{ "staffId": "...", "notes": "..." }
```

**Responses** — 200 KitchenOrder / 404 / 409 / 502

---

### POST /kitchen/orders/{orderId}/ready

Moves an order from PREPARING -> READY.

**Responses** — 200 KitchenOrder / 404 / 409 / 502

---

### POST /kitchen/orders/{orderId}/serve

Completes a dine-in order (READY -> COMPLETED). Removes from queue.

**Responses** — 200 KitchenOrder / 404 / 409 / 502

---

### POST /kitchen/orders/{orderId}/pickup

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

## Real-Time WebSocket (STOMP)

The service broadcasts a lightweight update message to `/topic/kitchen/{branchId}` whenever any order in that branch changes status.

```
Endpoint : ws://localhost:8084/api/ws   (or via gateway)
Protocol : STOMP over WebSocket
Topic    : /topic/kitchen/{branchId}
```

**Message payload**
```json
{ "orderId": "uuid", "status": "PREPARING" }
```

Connect and subscribe in the frontend:
```javascript
const client = new Client({ brokerURL: 'ws://gateway/api/ws' });
client.onConnect = () => {
  client.subscribe(`/topic/kitchen/${branchId}`, frame => {
    const { orderId, status } = JSON.parse(frame.body);
    // refresh the queue or update the specific card
  });
};
client.activate();
```

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
