package com.sevenmax.tracker.service;

import com.sevenmax.tracker.entity.*;
import com.sevenmax.tracker.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock PlayerTransferRepository transferRepository;
    @Mock AdminExpenseRepository adminExpenseRepository;
    @Mock ClubExpenseRepository clubExpenseRepository;
    @Mock UserRepository userRepository;
    @Mock BankAccountRepository bankAccountRepository;

    WalletService walletService;

    @BeforeEach
    void setUp() {
        walletService = new WalletService(
            transferRepository, adminExpenseRepository,
            clubExpenseRepository, userRepository, bankAccountRepository
        );
    }

    private PlayerTransfer transfer(String fromAdmin, String toAdmin, int amount) {
        PlayerTransfer t = new PlayerTransfer();
        t.setFromAdminUsername(fromAdmin);
        t.setToAdminUsername(toAdmin);
        t.setAmount(BigDecimal.valueOf(amount));
        t.setTransferDate(LocalDate.now());
        return t;
    }

    private AdminExpense paidExpense(String paidFromAdmin, int amount) {
        AdminExpense e = new AdminExpense();
        e.setAdminUsername("SomeAdmin");
        e.setAmount(BigDecimal.valueOf(amount));
        e.setPaidFromAdminUsername(paidFromAdmin);
        e.setSettled(true);
        return e;
    }

    @Test
    void balance_receivedCash_increasesBalance() {
        PlayerTransfer t = transfer(null, "Alice", 1000);
        when(transferRepository.findAll()).thenReturn(List.of(t));
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        BigDecimal balance = walletService.computeBalance("Alice");

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void balance_gaveCashOut_decreasesBalance() {
        PlayerTransfer t = transfer("Alice", null, 500);
        when(transferRepository.findAll()).thenReturn(List.of(t));
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        BigDecimal balance = walletService.computeBalance("Alice");

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(-500));
    }

    @Test
    void balance_adminToAdminTransfer_correctForBoth() {
        PlayerTransfer t = transfer("Alice", "Bob", 1000);
        when(transferRepository.findAll()).thenReturn(List.of(t));
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        assertThat(walletService.computeBalance("Alice")).isEqualByComparingTo(BigDecimal.valueOf(-1000));
        assertThat(walletService.computeBalance("Bob")).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }

    @Test
    void balance_paidExpense_decreasesBalance() {
        when(transferRepository.findAll()).thenReturn(List.of());
        when(adminExpenseRepository.findAll()).thenReturn(List.of(paidExpense("Alice", 200)));
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        BigDecimal balance = walletService.computeBalance("Alice");

        assertThat(balance).isEqualByComparingTo(BigDecimal.valueOf(-200));
    }

    @Test
    void balance_noActivity_isZero() {
        when(transferRepository.findAll()).thenReturn(List.of());
        when(adminExpenseRepository.findAll()).thenReturn(List.of());
        when(clubExpenseRepository.findAll()).thenReturn(List.of());

        assertThat(walletService.computeBalance("Alice")).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
