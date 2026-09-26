package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.Player;
import com.sevenmax.tracker.entity.Transaction;
import com.sevenmax.tracker.event.NewDepositEvent;
import com.sevenmax.tracker.repository.PlayerRepository;
import com.sevenmax.tracker.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AdminDepositServiceTest {

    @Mock PlayerRepository playerRepository;
    @Mock TransactionRepository transactionRepository;
    @Mock TransactionService transactionService;
    @Mock ApplicationEventPublisher eventPublisher;

    AdminDepositService service;

    @BeforeEach
    void setUp() {
        service = new AdminDepositService(playerRepository, transactionRepository, transactionService, eventPublisher);
    }

    private Player existingPlayer(long id, String username) {
        Player p = new Player();
        p.setId(id);
        p.setUsername(username);
        p.setBalance(BigDecimal.ZERO);
        return p;
    }

    @Test
    void createDepositForExistingPlayerCreatesTransactionAndPublishesEvent() {
        Player player = existingPlayer(7L, "existingUser");
        when(playerRepository.findById(7L)).thenReturn(Optional.of(player));
        when(transactionService.addTransaction(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = service.createDeposit(7L, null, BigDecimal.valueOf(100), "cash received");

        assertThat(result.getPlayer()).isEqualTo(player);
        assertThat(result.getType()).isEqualTo(Transaction.Type.ADMIN_DEPOSIT);
        assertThat(result.getMethod()).isEqualTo(Transaction.Method.MANUAL);
        assertThat(result.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100));
        assertThat(result.getNotes()).isEqualTo("cash received");
        assertThat(result.getChipsConfirmed()).isFalse();
        verify(eventPublisher).publishEvent(new NewDepositEvent("ADMIN"));
    }

    @Test
    void createDepositForNewUsernameCreatesPlayerStubFirst() {
        when(playerRepository.findByUsername("newJoiner")).thenReturn(Optional.empty());
        when(playerRepository.save(any(Player.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transactionService.addTransaction(any())).thenAnswer(inv -> inv.getArgument(0));

        Transaction result = service.createDeposit(null, "newJoiner", BigDecimal.valueOf(50), null);

        ArgumentCaptor<Player> playerCaptor = ArgumentCaptor.forClass(Player.class);
        verify(playerRepository).save(playerCaptor.capture());
        assertThat(playerCaptor.getValue().getUsername()).isEqualTo("newJoiner");
        assertThat(result.getPlayer().getUsername()).isEqualTo("newJoiner");
    }

    @Test
    void createDepositForNewUsernameReusesExistingStubInsteadOfDuplicating() {
        Player existingStub = existingPlayer(9L, "alreadyThere");
        when(playerRepository.findByUsername("alreadyThere")).thenReturn(Optional.of(existingStub));
        when(transactionService.addTransaction(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createDeposit(null, "alreadyThere", BigDecimal.valueOf(50), null);

        verify(playerRepository, never()).save(any());
    }

    @Test
    void createDepositRejectsWhenNeitherPlayerIdNorUsernameGiven() {
        assertThatThrownBy(() -> service.createDeposit(null, null, BigDecimal.valueOf(50), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDepositRejectsWhenBothPlayerIdAndUsernameGiven() {
        assertThatThrownBy(() -> service.createDeposit(1L, "someone", BigDecimal.valueOf(50), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createDepositRejectsAmountBelowMinimum() {
        assertThatThrownBy(() -> service.createDeposit(1L, null, BigDecimal.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Minimum deposit is 1");
    }

    @Test
    void confirmChipsMarksTransactionConfirmed() {
        Transaction tx = new Transaction();
        tx.setType(Transaction.Type.ADMIN_DEPOSIT);
        tx.setChipsConfirmed(false);
        when(transactionRepository.findById(5L)).thenReturn(Optional.of(tx));
        when(transactionRepository.save(tx)).thenReturn(tx);

        Transaction result = service.confirmChips(5L);

        assertThat(result.getChipsConfirmed()).isTrue();
    }

    @Test
    void confirmChipsRejectsNonAdminDepositTransaction() {
        Transaction tx = new Transaction();
        tx.setType(Transaction.Type.KASHCASH_DEPOSIT);
        when(transactionRepository.findById(5L)).thenReturn(Optional.of(tx));

        assertThatThrownBy(() -> service.confirmChips(5L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
