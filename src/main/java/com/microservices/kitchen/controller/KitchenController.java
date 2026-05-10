package com.microservices.kitchen.controller;

import com.microservices.kitchen.dto.KitchenDtos;
import com.microservices.kitchen.service.KitchenQueueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestController
@RequestMapping("/v1/kitchen")
@Tag(name = "Kitchen", description = "Kitchen display and workflow endpoints. Staff use these to view the live order queue and drive orders through the preparation lifecycle: Accept → Ready → Serve/Pickup.")
public class KitchenController {

    @Autowired
    private KitchenQueueService kitchenQueueService;

    // ── GET /kitchen/queue  (branchId from header or query param) ────────────

    @Operation(
        summary = "Get kitchen queue (branchId from header or query param)",
        description = "Frontend-friendly variant: resolves branchId from the `X-User-BranchId` request header "
            + "(set by the API gateway from the JWT) or from the `?branchId=` query parameter. "
            + "Query param takes priority over the header. "
            + "Returns the same payload as GET /kitchen/queue/{branchId}.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Live kitchen queue grouped by status"),
        @ApiResponse(responseCode = "400", description = "branchId not provided via header or query param")
    })
    @GetMapping("/queue")
    public ResponseEntity<KitchenDtos.KitchenQueueResponse> getQueueFromHeader(
            @RequestHeader(value = "X-User-BranchId", required = false) String branchIdHeader,
            @RequestParam(required = false) String branchId) {
        // query param takes priority, then header
        String resolvedBranchId = branchId != null ? branchId : branchIdHeader;
        if (resolvedBranchId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "branchId required (header X-User-BranchId or ?branchId= query param)");
        }
        log.info("GET kitchen queue branchId={} (from header/param)", resolvedBranchId);
        return ResponseEntity.ok(kitchenQueueService.getQueue(resolvedBranchId));
    }

    // ── GET /kitchen/queue/{branchId} ─────────────────────────────────────────

    @Operation(
        summary = "Get kitchen queue for a branch",
        description = "Returns all active orders for the specified branch, grouped into three columns: RECEIVED (awaiting acceptance), PREPARING (in progress), and READY (waiting for pickup or service). "
            + "Orders within each column are sorted oldest-first so kitchen staff see the most urgent items at the top. "
            + "Subscribe to the WebSocket topic `/topic/kitchen/{branchId}` to receive real-time push updates whenever this queue changes.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Live kitchen queue grouped by status")
    })
    @GetMapping("/queue/{branchId}")
    public ResponseEntity<KitchenDtos.KitchenQueueResponse> getQueue(
            @Parameter(description = "UUID of the branch whose queue to fetch", required = true)
            @PathVariable String branchId) {
        log.info("GET kitchen queue branchId={}", branchId);
        return ResponseEntity.ok(kitchenQueueService.getQueue(branchId));
    }

    // ── POST /kitchen/orders/{orderId}/accept ─────────────────────────────────

    @Operation(
        summary = "Accept an order (RECEIVED → PREPARING)",
        description = "Kitchen staff tap this to acknowledge they have seen the order and are starting preparation. "
            + "Moves the order from the RECEIVED column to the PREPARING column in Redis, "
            + "calls order-service to persist the PREPARING status (which publishes an `order.status.updated` Kafka event), "
            + "and broadcasts the change over WebSocket to all connected kitchen displays for this branch.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order moved to PREPARING"),
        @ApiResponse(responseCode = "404", description = "Order not found in kitchen queue"),
        @ApiResponse(responseCode = "409", description = "Order is not in RECEIVED status"),
        @ApiResponse(responseCode = "502", description = "Order-service could not be reached to persist the status change")
    })
    @PostMapping("/orders/{orderId}/accept")
    public ResponseEntity<KitchenDtos.KitchenOrder> acceptOrder(
            @Parameter(description = "UUID of the order to accept", required = true)
            @PathVariable String orderId,
            @RequestBody(required = false) KitchenDtos.KitchenActionRequest request) {
        log.info("ACCEPT order={}", orderId);
        String staffId = request != null ? request.getStaffId() : null;
        String notes   = request != null ? request.getNotes()   : null;
        return ResponseEntity.ok(kitchenQueueService.acceptOrder(orderId, staffId, notes));
    }

    // ── POST /kitchen/orders/{orderId}/ready ──────────────────────────────────

    @Operation(
        summary = "Mark an order as ready (PREPARING → READY)",
        description = "Kitchen staff tap this when all items for the order are prepared and plated. "
            + "Moves the order to the READY column, calls order-service to persist READY status. "
            + "Order-service also publishes to the dedicated `order.ready` Kafka topic, which is used for priority routing to the customer notification system.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order moved to READY"),
        @ApiResponse(responseCode = "404", description = "Order not found in kitchen queue"),
        @ApiResponse(responseCode = "409", description = "Order is not in PREPARING status"),
        @ApiResponse(responseCode = "502", description = "Order-service could not be reached")
    })
    @PostMapping("/orders/{orderId}/ready")
    public ResponseEntity<KitchenDtos.KitchenOrder> markReady(
            @Parameter(description = "UUID of the order to mark ready", required = true)
            @PathVariable String orderId,
            @RequestBody(required = false) KitchenDtos.KitchenActionRequest request) {
        log.info("READY order={}", orderId);
        String staffId = request != null ? request.getStaffId() : null;
        String notes   = request != null ? request.getNotes()   : null;
        return ResponseEntity.ok(kitchenQueueService.markReady(orderId, staffId, notes));
    }

    // ── POST /kitchen/orders/{orderId}/serve ──────────────────────────────────

    @Operation(
        summary = "Serve a dine-in order (READY → COMPLETED)",
        description = "Used for DINE_IN orders when the food is physically brought to the table. "
            + "Removes the order from the kitchen queue entirely and calls order-service to mark it COMPLETED. "
            + "The customer notification layer (when implemented) will send a 'enjoy your meal' message.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order completed — served to customer"),
        @ApiResponse(responseCode = "404", description = "Order not found in kitchen queue"),
        @ApiResponse(responseCode = "409", description = "Order is not in READY status"),
        @ApiResponse(responseCode = "502", description = "Order-service could not be reached")
    })
    @PostMapping("/orders/{orderId}/serve")
    public ResponseEntity<KitchenDtos.KitchenOrder> serveOrder(
            @Parameter(description = "UUID of the order to serve", required = true)
            @PathVariable String orderId,
            @RequestBody(required = false) KitchenDtos.KitchenActionRequest request) {
        log.info("SERVE order={}", orderId);
        String staffId = request != null ? request.getStaffId() : null;
        String notes   = request != null ? request.getNotes()   : null;
        return ResponseEntity.ok(kitchenQueueService.serveOrder(orderId, staffId, notes));
    }

    // ── POST /kitchen/orders/{orderId}/pickup ─────────────────────────────────

    @Operation(
        summary = "Mark a takeaway/delivery order as picked up (READY → COMPLETED)",
        description = "Used for TAKEAWAY and DELIVERY orders when the customer or rider collects the food from the counter. "
            + "Removes the order from the kitchen queue and calls order-service to mark it COMPLETED. "
            + "The customer notification layer (when implemented) will send a 'your order is on its way' message for DELIVERY orders.",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Order completed — picked up"),
        @ApiResponse(responseCode = "404", description = "Order not found in kitchen queue"),
        @ApiResponse(responseCode = "409", description = "Order is not in READY status"),
        @ApiResponse(responseCode = "502", description = "Order-service could not be reached")
    })
    @PostMapping("/orders/{orderId}/pickup")
    public ResponseEntity<KitchenDtos.KitchenOrder> pickupOrder(
            @Parameter(description = "UUID of the order to mark as picked up", required = true)
            @PathVariable String orderId,
            @RequestBody(required = false) KitchenDtos.KitchenActionRequest request) {
        log.info("PICKUP order={}", orderId);
        String staffId = request != null ? request.getStaffId() : null;
        String notes   = request != null ? request.getNotes()   : null;
        return ResponseEntity.ok(kitchenQueueService.pickupOrder(orderId, staffId, notes));
    }

    // ── PATCH /kitchen/orders/{orderId}/status ────────────────────────────────

    @Operation(
        summary = "Update order status (unified)",
        description = "Frontend-friendly single endpoint that drives an order through its full lifecycle. "
            + "Pass `newStatus` in the request body: "
            + "`PREPARING` — accepts the order (RECEIVED → PREPARING); "
            + "`READY` — marks food done (PREPARING → READY); "
            + "`SERVED` — completes a dine-in order (READY → COMPLETED); "
            + "`PICKED_UP` — completes a takeaway/delivery order (READY → COMPLETED).",
        security = @SecurityRequirement(name = "Bearer Authentication"))
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status updated successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid newStatus value"),
        @ApiResponse(responseCode = "404", description = "Order not found in kitchen queue"),
        @ApiResponse(responseCode = "409", description = "Invalid status transition"),
        @ApiResponse(responseCode = "502", description = "Order-service could not be reached")
    })
    @PatchMapping("/orders/{orderId}/status")
    public ResponseEntity<KitchenDtos.KitchenOrder> updateStatus(
            @Parameter(description = "UUID of the order to update", required = true)
            @PathVariable String orderId,
            @RequestBody KitchenDtos.KitchenStatusUpdateRequest request) {
        log.info("PATCH kitchen order status orderId={} newStatus={}", orderId, request.newStatus());
        String staffId = request.staffId();
        String notes   = request.notes();
        return switch (request.newStatus().toUpperCase()) {
            case "PREPARING" -> ResponseEntity.ok(kitchenQueueService.acceptOrder(orderId, staffId, notes));
            case "READY"     -> ResponseEntity.ok(kitchenQueueService.markReady(orderId, staffId, notes));
            case "SERVED"    -> ResponseEntity.ok(kitchenQueueService.serveOrder(orderId, staffId, notes));
            case "PICKED_UP" -> ResponseEntity.ok(kitchenQueueService.pickupOrder(orderId, staffId, notes));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid newStatus. Use: PREPARING, READY, SERVED, PICKED_UP");
        };
    }
}
