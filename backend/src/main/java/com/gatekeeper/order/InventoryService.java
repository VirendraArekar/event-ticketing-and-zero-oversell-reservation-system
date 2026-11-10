package com.gatekeeper.order;

import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.common.exception.NotFoundException;
import com.gatekeeper.event.TicketInventoryEntity;
import com.gatekeeper.event.TicketInventoryRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class InventoryService {

    private final TicketInventoryRepository ticketInventoryRepository;

    public InventoryService(TicketInventoryRepository ticketInventoryRepository) {
        this.ticketInventoryRepository = ticketInventoryRepository;
    }

    public record SeatRequest(UUID ticketTypeId, String ticketTypeName, int quantity) {
    }

    /**
     * Atomically reserves held seats in MySQL using conditional UPDATE statements
     * ordered deterministically by ticketTypeId to avoid deadlocks across multi-item orders.
     */
    public void reserveSeatsInMySql(List<SeatRequest> requests, Instant now) {
        List<SeatRequest> sorted = requests.stream()
                .sorted(Comparator.comparing(r -> r.ticketTypeId().toString()))
                .toList();

        for (SeatRequest req : sorted) {
            int updated = ticketInventoryRepository.holdSeats(req.ticketTypeId().toString(), req.quantity(), now);
            if (updated == 0) {
                TicketInventoryEntity inv = ticketInventoryRepository.findById(req.ticketTypeId())
                        .orElseThrow(() -> new NotFoundException("Ticket inventory not found"));
                int remaining = inv.getAvailable();
                if (remaining <= 0) {
                    throw new ConflictException(req.ticketTypeName() + " is sold out");
                }
                throw new ConflictException("Only " + remaining + " left for " + req.ticketTypeName());
            }
        }
    }

    public void confirmHeldSeatsInMySql(List<SeatRequest> requests, Instant now) {
        List<SeatRequest> sorted = requests.stream()
                .sorted(Comparator.comparing(r -> r.ticketTypeId().toString()))
                .toList();

        for (SeatRequest req : sorted) {
            int updated = ticketInventoryRepository.confirmHoldToSold(
                    req.ticketTypeId().toString(), req.quantity(), now);
            if (updated == 0) {
                throw new ConflictException("Held inventory could not be confirmed for " + req.ticketTypeName());
            }
        }
    }

    public void releaseHeldSeatsInMySql(List<SeatRequest> requests, Instant now) {
        List<SeatRequest> sorted = requests.stream()
                .sorted(Comparator.comparing(r -> r.ticketTypeId().toString()))
                .toList();

        for (SeatRequest req : sorted) {
            int updated = ticketInventoryRepository.releaseHold(
                    req.ticketTypeId().toString(), req.quantity(), now);
            if (updated == 0) {
                throw new ConflictException("Held inventory could not be released for " + req.ticketTypeName());
            }
        }
    }

    public void refundSoldSeatsInMySql(List<SeatRequest> requests, Instant now) {
        List<SeatRequest> sorted = requests.stream()
                .sorted(Comparator.comparing(r -> r.ticketTypeId().toString()))
                .toList();

        for (SeatRequest req : sorted) {
            int updated = ticketInventoryRepository.refundSoldSeats(
                    req.ticketTypeId().toString(), req.quantity(), now);
            if (updated == 0) {
                throw new ConflictException("Sold inventory could not be refunded for " + req.ticketTypeName());
            }
        }
    }
}

