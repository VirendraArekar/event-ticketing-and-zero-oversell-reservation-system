package com.gatekeeper.order;

import com.gatekeeper.common.exception.ConflictException;
import com.gatekeeper.event.TicketInventoryEntity;
import com.gatekeeper.event.TicketInventoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryMathAndPaymentUnitTest {

    @Mock
    private TicketInventoryRepository inventoryRepository;

    @Test
    void offlinePaymentProviderConfirmsFreeImmediatelyAndPendsPaidOrders() {
        OfflinePaymentProvider provider = new OfflinePaymentProvider(86400);
        Instant now = Instant.now();
        OrderEntity freeOrder = new OrderEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OrderStatus.HELD, 0L, "USD", "k1", now.plusSeconds(600), now
        );
        OrderEntity paidOrder = new OrderEntity(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                OrderStatus.HELD, 5000L, "USD", "k2", now.plusSeconds(600), now
        );

        PaymentProvider.PaymentResult freeRes = provider.processConfirmation(freeOrder, freeOrder.getUserId(), now);
        assertThat(freeRes.decision()).isEqualTo(PaymentProvider.PaymentDecision.CAPTURED);
        assertThat(freeRes.paymentDeadlineAt()).isNull();

        PaymentProvider.PaymentResult paidRes = provider.processConfirmation(paidOrder, paidOrder.getUserId(), now);
        assertThat(paidRes.decision()).isEqualTo(PaymentProvider.PaymentDecision.PENDING_PAYMENT);
        assertThat(paidRes.paymentDeadlineAt()).isEqualTo(now.plusSeconds(86400));
    }

    @Test
    void inventoryServiceLocksRowsInDeterministicUuidOrderAndReportsRemainingSeats() {
        InventoryService service = new InventoryService(inventoryRepository);
        UUID idHigh = UUID.fromString("ffffffff-0000-0000-0000-000000000001");
        UUID idLow = UUID.fromString("00000001-0000-0000-0000-000000000001");
        Instant now = Instant.now();

        when(inventoryRepository.holdSeats(eq(idLow.toString()), eq(2), any())).thenReturn(1);
        when(inventoryRepository.holdSeats(eq(idHigh.toString()), eq(1), any())).thenReturn(1);

        service.reserveSeatsInMySql(List.of(
                new InventoryService.SeatRequest(idHigh, "VIP", 1),
                new InventoryService.SeatRequest(idLow, "General", 2)
        ), now);

        InOrder inOrder = Mockito.inOrder(inventoryRepository);
        inOrder.verify(inventoryRepository).holdSeats(eq(idLow.toString()), eq(2), any());
        inOrder.verify(inventoryRepository).holdSeats(eq(idHigh.toString()), eq(1), any());

        TicketInventoryEntity remainingThree = new TicketInventoryEntity(idLow, UUID.randomUUID(), 10, now);
        // Simulate 7 sold via reflection or direct update? Wait: verify error message when 0 rows updated
        when(inventoryRepository.holdSeats(eq(idLow.toString()), eq(5), any())).thenReturn(0);
        when(inventoryRepository.findById(idLow)).thenReturn(Optional.of(remainingThree));

        assertThatThrownBy(() -> service.reserveSeatsInMySql(
                List.of(new InventoryService.SeatRequest(idLow, "General", 5)), now))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Only 10 left");
    }
}

