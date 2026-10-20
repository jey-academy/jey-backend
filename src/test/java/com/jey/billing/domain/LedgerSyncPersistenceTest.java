package com.jey.billing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import com.jey.TestcontainersConfiguration;
import com.jey.billing.api.PaymentMethod;
import com.jey.core.shared.Money;
import com.jey.core.shared.UniqueKeys;

// 결제마다 장부 반영 상태 한 줄. 같은 결제의 줄은 하나이고, 한 번에 한 쪽만 맡는다.
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class LedgerSyncPersistenceTest {

	private static final Instant AT = Instant.parse("2026-10-31T03:00:00.123456Z");

	@Autowired
	LedgerSyncRepository syncs;

	@Autowired
	InvoiceRepository invoices;

	@Autowired
	PaymentRepository payments;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 저장한_줄을_다시_읽으면_모든_칸이_같다() {
		long paymentId = newPayment();
		long id = transaction.execute(status -> {
			LedgerSync sync = syncs.save(LedgerSync.pending(paymentId, 1L, 2L, YearMonth.of(2026, 11), AT));
			sync.claim(AT);
			sync.written("2026-11", 10, AT);
			sync.requestCancel(LocalDate.of(2026, 11, 1), AT);
			return sync.getId();
		});

		LedgerSync read = syncs.findByPaymentId(paymentId).orElseThrow();
		assertThat(read.getId()).isEqualTo(id);
		assertThat(read.getCampusId()).isEqualTo(1L);
		assertThat(read.getStudentId()).isEqualTo(2L);
		assertThat(read.getTargetMonth()).isEqualTo(YearMonth.of(2026, 11));
		assertThat(read.getStatus()).isEqualTo(LedgerSyncStatus.PENDING);
		assertThat(read.getAttempts()).isZero();
		assertThat(read.getLastAttemptAt()).isEqualTo(AT);
		assertThat(read.getNextAttemptAt()).isEqualTo(AT);
		assertThat(read.getSheetTab()).isEqualTo("2026-11");
		assertThat(read.getSheetRow()).isEqualTo(10);
		assertThat(read.getSyncedAt()).isEqualTo(AT);
		assertThat(read.getCanceledOn()).isEqualTo(LocalDate.of(2026, 11, 1));
		assertThat(read.isCancelMarked()).isFalse();
	}

	@Test
	void 같은_결제의_줄은_하나다() {
		long paymentId = newPayment();
		syncs.save(LedgerSync.pending(paymentId, 1L, 2L, YearMonth.of(2026, 11), AT));

		assertThatThrownBy(() -> syncs.saveAndFlush(LedgerSync.pending(paymentId, 1L, 2L, YearMonth.of(2026, 11), AT)))
			.isInstanceOfSatisfying(DataIntegrityViolationException.class,
					e -> assertThat(UniqueKeys.isViolationOf(e, LedgerSync.PAYMENT_UNIQUE_KEY)).isTrue());
	}

	@Test
	void 미반영_이유는_미반영일_때만_있다() {
		long paymentId = newPayment();
		long id = syncs.save(LedgerSync.pending(paymentId, 1L, 2L, YearMonth.of(2026, 11), AT)).getId();

		assertThatThrownBy(() -> jdbc.update("UPDATE billing_ledger_sync SET reason = 'NO_EMPTY_ROW' WHERE id = ?", id))
			.hasMessageContaining("ck_billing_ledger_sync_reason");
		assertThatThrownBy(() -> jdbc.update("UPDATE billing_ledger_sync SET status = 'UNSYNCED' WHERE id = ?", id))
			.hasMessageContaining("ck_billing_ledger_sync_reason");
	}

	@Test
	void 동시에_맡으려_하면_한_쪽만_맡는다() throws Exception {
		for (int round = 0; round < 5; round++) {
			long id = syncs.save(LedgerSync.pending(newPayment(), 1L, 2L, YearMonth.of(2026, 11), AT)).getId();
			Instant now = AT.plusSeconds(1);
			CountDownLatch start = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				List<Future<Boolean>> results = new ArrayList<>();
				Callable<Boolean> claim = () -> {
					start.await();
					return transaction.execute(status -> syncs.findByIdForUpdate(id).orElseThrow().claim(now));
				};
				results.add(pool.submit(claim));
				results.add(pool.submit(claim));
				start.countDown();
				int claimed = 0;
				for (Future<Boolean> result : results) {
					claimed += result.get(30, TimeUnit.SECONDS) ? 1 : 0;
				}
				assertThat(claimed).isEqualTo(1);
				assertThat(syncs.findById(id).orElseThrow().getAttempts()).isEqualTo(1);
			} finally {
				pool.shutdownNow();
			}
		}
	}

	@Test
	void 때가_된_대기_줄과_오래된_시도_중인_줄만_집는다() {
		Instant now = Instant.parse("2030-01-01T00:00:00Z");
		long due = syncs.save(LedgerSync.pending(newPayment(), 1L, 2L, YearMonth.of(2026, 11), now)).getId();
		long notYet = syncs.save(LedgerSync.pending(newPayment(), 1L, 2L, YearMonth.of(2026, 11), now.plusSeconds(1)))
			.getId();
		long stale = transaction.execute(status -> {
			LedgerSync sync = syncs
				.save(LedgerSync.pending(newPayment(), 1L, 2L, YearMonth.of(2026, 11), now.minusSeconds(3600)));
			sync.claim(now.minus(LedgerSync.STALE_AFTER).minusSeconds(1));
			return sync.getId();
		});
		long fresh = transaction.execute(status -> {
			LedgerSync sync = syncs
				.save(LedgerSync.pending(newPayment(), 1L, 2L, YearMonth.of(2026, 11), now.minusSeconds(3600)));
			sync.claim(now.minusSeconds(10));
			return sync.getId();
		});

		List<Long> ids = syncs.findDueIds(now, now.minus(LedgerSync.STALE_AFTER), PageRequest.of(0, 1000));

		assertThat(ids).contains(due, stale).doesNotContain(notYet, fresh);
	}

	private long newPayment() {
		long studentId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE / 2);
		Money fee = Money.of(280_000);
		InvoiceLine line = InvoiceLine.of(1, 8, fee, TuitionCalculator.charge(fee, 8, List.of(), DiscountPolicy.ADDITIVE));
		long invoiceId = invoices
			.save(Invoice.issue(1, studentId, YearMonth.of(2026, 11), DiscountPolicy.ADDITIVE, List.of(line)))
			.getId();
		return transaction.execute(status -> payments
			.save(Payment.record(invoices.findById(invoiceId).orElseThrow(), PaymentMethod.CASH, fee, Money.ZERO, AT))
			.getId());
	}

}
