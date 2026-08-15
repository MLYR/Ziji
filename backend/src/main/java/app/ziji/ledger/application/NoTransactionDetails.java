package app.ziji.ledger.application;

/** 无一对一明细的收入或支出交易。 */
public record NoTransactionDetails() implements TransactionWriteDetails {
}
