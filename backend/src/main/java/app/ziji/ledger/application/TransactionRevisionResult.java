package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Transaction;

/** 修订后返回三段事实链中新增的冲正和替代交易。 */
public record TransactionRevisionResult(UUID originalTransactionId, Transaction reversal, Transaction replacement) {
}
