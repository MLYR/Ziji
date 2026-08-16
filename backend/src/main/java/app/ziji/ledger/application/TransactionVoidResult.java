package app.ziji.ledger.application;

import java.util.UUID;

import app.ziji.ledger.domain.Transaction;

/** 作废后返回新增冲正交易，原交易仍作为不可变历史事实保留。 */
public record TransactionVoidResult(UUID originalTransactionId, Transaction reversal) {
}
