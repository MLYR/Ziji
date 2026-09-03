package app.ziji.investment.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import app.ziji.account.application.AccountPostingReference;
import app.ziji.account.application.AccountPostingReferencePort;
import app.ziji.accountmember.application.AccountInclusionReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountPostingAccessPort;
import app.ziji.investment.domain.InstrumentType;
import app.ziji.investment.domain.InvestmentSide;
import app.ziji.investment.domain.InvestmentTrade;
import app.ziji.investment.domain.ModifiedDietzCalculator;
import app.ziji.investment.domain.Position;
import app.ziji.investment.domain.PositionCalculator;
import app.ziji.investment.domain.ReturnStatus;
import app.ziji.investment.domain.ValuationStatus;
import app.ziji.investment.domain.XirrCalculator;
import app.ziji.investment.domain.XirrStatus;
import app.ziji.ledger.application.InvestmentCashReadPort;
import app.ziji.ledger.application.InvestmentLedgerCommand;
import app.ziji.ledger.application.InvestmentLedgerPort;
import app.ziji.ledger.domain.CurrencyCode;
import app.ziji.shared.application.TransactionRunner;
import app.ziji.user.application.CurrentUserBaseCurrencyPort;
import app.ziji.user.application.CurrentUserTimezonePort;

/** 投资应用编排；金额、权限和估值均通过公开端口组合，不访问其他模块的表。 */
public class InvestmentApplicationService implements InvestmentDashboardPort {

	private final AccountMembershipReadPort memberships;
	private final AccountInclusionReadPort inclusions;
	private final AccountPostingReferencePort accounts;
	private final AccountPostingAccessPort postingAccess;
	private final InvestmentFactReadPort facts;
	private final InvestmentLedgerPort ledger;
	private final InvestmentMarketDataPort marketData;
	private final InvestmentCashReadPort cashBalances;
	private final InvestmentExternalCashFlowPort externalCashFlows;
	private final InvestmentExchangeRatePort exchangeRates;
	private final InvestmentValuationRevisionPort valuationRevisions;
	private final TransactionRunner transactions;
	private final CurrentUserBaseCurrencyPort baseCurrencies;
	private final CurrentUserTimezonePort timezones;
	private final Clock clock;
	private final PositionCalculator positions = new PositionCalculator();
	private final ModifiedDietzCalculator modifiedDietz = new ModifiedDietzCalculator();
	private final XirrCalculator xirr = new XirrCalculator();

	public InvestmentApplicationService(
		AccountMembershipReadPort memberships,
		AccountInclusionReadPort inclusions,
		AccountPostingReferencePort accounts,
		AccountPostingAccessPort postingAccess,
		InvestmentFactReadPort facts,
		InvestmentLedgerPort ledger,
		InvestmentMarketDataPort marketData,
		InvestmentCashReadPort cashBalances,
		InvestmentExternalCashFlowPort externalCashFlows,
		InvestmentExchangeRatePort exchangeRates,
		InvestmentValuationRevisionPort valuationRevisions,
		TransactionRunner transactions,
		CurrentUserBaseCurrencyPort baseCurrencies,
		CurrentUserTimezonePort timezones,
		Clock clock) {
		this.memberships = Objects.requireNonNull(memberships, "投资成员读取入口不能为空。");
		this.inclusions = Objects.requireNonNull(inclusions, "投资历史计入入口不能为空。");
		this.accounts = Objects.requireNonNull(accounts, "投资账户读取入口不能为空。");
		this.postingAccess = Objects.requireNonNull(postingAccess, "投资写入权限入口不能为空。");
		this.facts = Objects.requireNonNull(facts, "投资事实读取入口不能为空。");
		this.ledger = Objects.requireNonNull(ledger, "投资 Ledger 入口不能为空。");
		this.marketData = Objects.requireNonNull(marketData, "投资行情入口不能为空。");
		this.cashBalances = Objects.requireNonNull(cashBalances, "投资现金入口不能为空。");
		this.externalCashFlows = Objects.requireNonNull(externalCashFlows, "投资边界现金流入口不能为空。");
		this.exchangeRates = Objects.requireNonNull(exchangeRates, "投资汇率入口不能为空。");
		this.valuationRevisions = Objects.requireNonNull(valuationRevisions, "收益日历修订入口不能为空。");
		this.transactions = Objects.requireNonNull(transactions, "收益日历事务入口不能为空。");
		this.baseCurrencies = Objects.requireNonNull(baseCurrencies, "基准币种入口不能为空。");
		this.timezones = Objects.requireNonNull(timezones, "用户时区入口不能为空。");
		this.clock = Objects.requireNonNull(clock, "投资时钟不能为空。");
	}

	public InvestmentTradeResult createTrade(InvestmentTradeCommand command) {
		if (command == null) {
			throw new InvestmentRequestValidationException("投资成交命令不能为空。");
		}
		AccountPostingReference account = requireWritableInvestmentAccount(command.userId(), command.investmentAccountId());
		InvestmentFactReadPort.InstrumentSnapshot instrument = facts.findInstrument(command.instrumentId())
			.orElseThrow(() -> new InvestmentNotVisibleException());
		if (!"ACTIVE".equals(instrument.status())) {
				throw new InvestmentBusinessRuleException("停用或退市产品不能新增投资成交。");
		}
		if (!account.currency().equals(command.currency()) || !instrument.currency().equals(command.currency())) {
			throw new InvestmentBusinessRuleException("投资账户、产品和成交币种必须一致。");
		}
		CurrencyCode currency = CurrencyCode.fromCode(command.currency());
		BigDecimal grossAmount = grossAmount(command, currency);
		BigDecimal sellCostBasis = BigDecimal.ZERO;
		if (command.side() == InvestmentSide.SELL) {
			Position current = positionBefore(command.investmentAccountId(), command.instrumentId(), command.tradeAt());
			try {
				sellCostBasis = round(current.sell(command.quantity()).releasedCost(), currency);
			} catch (RuntimeException exception) {
				throw new InvestmentBusinessRuleException("卖出数量超过当前持仓。");
			}
		}
		InvestmentLedgerCommand ledgerCommand = new InvestmentLedgerCommand(
			command.userId(), command.tradeId(), command.investmentAccountId(), command.instrumentId(),
			InvestmentLedgerCommand.Side.valueOf(command.side().name()), command.quantity(), command.unitPrice(),
			grossAmount, command.feeAmount(), command.taxAmount(), sellCostBasis, currency,
			command.tradeAt(), command.tradeAt().atZone(ZoneId.of(command.timezone())).toLocalDate(), command.timezone(), command.note());
		var ledgerResult = ledger.postInvestmentTrade(ledgerCommand);
		return new InvestmentTradeResult(
			command.tradeId(), ledgerResult.transactionId(), command.investmentAccountId(), command.instrumentId(), command.side(),
			command.quantity(), command.unitPrice(), command.currency(), grossAmount, command.feeAmount(), command.taxAmount(), command.tradeAt());
	}

	public List<InvestmentTradeResult> listTrades(UUID userId, UUID accountId, LocalDate from, LocalDate to, int limit) {
		if (limit < 1 || limit > 200) {
			throw new InvestmentRequestValidationException("查询数量必须在 1 到 200 之间。");
		}
		List<AccountView> visibleAccounts = accountId == null
			? visibleInvestmentAccounts(userId, false)
			: List.of(requireReadableInvestmentAccount(userId, accountId));
		List<InvestmentTradeResult> result = new ArrayList<>();
		for (AccountView account : visibleAccounts) {
			result.addAll(facts.listTrades(account.id(), clock.instant(), from, to).stream()
				.map(InvestmentApplicationService::tradeResult).toList());
		}
		return result.stream().sorted(Comparator.comparing(InvestmentTradeResult::tradeAt).reversed())
			.limit(limit).toList();
	}

	public List<InvestmentPositionResult> listPositions(UUID userId, UUID accountId, Instant asOf, int limit) {
		if (limit < 1 || limit > 200) {
			throw new InvestmentRequestValidationException("查询数量必须在 1 到 200 之间。");
		}
		AccountView account = requireReadableInvestmentAccount(userId, accountId);
		Instant evaluation = asOf == null ? clock.instant() : asOf;
		ZoneId zone = timezones.currentTimezone(userId);
		Map<UUID, Position> rebuilt = rebuildPositions(account.id(), evaluation);
		List<InvestmentPositionResult> result = new ArrayList<>();
		for (Position position : rebuilt.values()) {
			if (position.quantity().signum() == 0) {
				continue;
			}
			result.add(positionResult(position, account.currency(), evaluation, zone));
		}
		return result.stream().sorted(Comparator.comparing(InvestmentPositionResult::instrumentId)).limit(limit).toList();
	}

	public InvestmentPerformanceResult performance(UUID userId, UUID accountId, Instant from, Instant to) {
		AccountView account = requireReadableInvestmentAccount(userId, accountId);
		Instant evaluation = to == null ? clock.instant() : to;
		ZoneId zone = timezones.currentTimezone(userId);
		List<InvestmentTrade> trades = facts.listTrades(account.id(), evaluation, null, null);
		PerformanceParts parts = performanceParts(trades, account.currency(), evaluation, zone);
		BigDecimal terminalValue = cashBalances.findCashBalance(account.id(), evaluation).amount();
		if (!parts.unpriced()) {
			terminalValue = terminalValue.add(parts.unrealizedMarketValue());
		}
		Instant start = from == null ? Instant.EPOCH : from;
		List<XirrCalculator.CashFlow> flows = new ArrayList<>();
		for (InvestmentExternalCashFlowPort.CashFlow flow : externalCashFlows.list(account.id(), start, evaluation)) {
			// Ledger 端口的正值表示资金流入投资账户，XIRR 使用投资者视角所以反号。
			flows.add(new XirrCalculator.CashFlow(flow.occurredAt(), flow.amount().negate()));
		}
		if (!parts.unpriced() && terminalValue.signum() != 0) {
			flows.add(new XirrCalculator.CashFlow(evaluation, terminalValue));
		}
		XirrCalculator.Result xirrResult = parts.unpriced()
			? XirrCalculator.Result.failed(XirrStatus.INVALID_CASH_FLOWS) : xirr.calculate(flows);
		XirrStatus xirrStatus = xirrResult.status();
		BigDecimal cumulative = parts.realizedProfit().add(parts.unrealizedProfit()).add(parts.dividends())
			.subtract(parts.nonSellFees()).subtract(parts.nonSellTaxes());
		BigDecimal returnRate = parts.investedCapital().signum() <= 0
			? null : cumulative.divide(parts.investedCapital(), 24, RoundingMode.HALF_UP);
		return new InvestmentPerformanceResult(
			account.currency(), parts.realizedProfit(), parts.unrealizedProfit(), parts.dividends(), parts.fees(), parts.taxes(),
			cumulative, returnRate, null, xirrResult.rate(), xirrStatus);
	}

	public InvestmentOverviewResult overview(UUID userId, Instant asOf) {
		if (userId == null) {
			throw new InvestmentRequestValidationException("当前用户不能为空。");
		}
		Instant evaluation = asOf == null ? clock.instant() : asOf;
		String baseCurrency = baseCurrencies.currentBaseCurrency(userId);
		BigDecimal brokerCash = BigDecimal.ZERO;
		BigDecimal marketValue = BigDecimal.ZERO;
		int unpriced = 0;
		int stale = 0;
		for (AccountView account : includedInvestmentAccountsAt(userId, evaluation)) {
			Optional<BigDecimal> cash = convert(cashBalances.findCashBalance(account.id(), evaluation).amount(), account.currency(), baseCurrency, evaluation)
				.map(value -> value.multiply(account.inclusionRatio()));
			if (cash.isEmpty()) {
				unpriced++;
			} else {
				brokerCash = brokerCash.add(cash.get());
			}
				for (InvestmentPositionResult position : listPositions(userId, account.id(), evaluation, 200)) {
					if (position.marketValue() == null) {
						unpriced++;
						continue;
					}
					if (position.priceFreshness() == InvestmentMarketDataPort.Freshness.STALE) {
						stale++;
					}
				Optional<BigDecimal> converted = convert(position.marketValue(), position.currency(), baseCurrency, evaluation)
					.map(value -> value.multiply(account.inclusionRatio()));
				if (converted.isEmpty()) {
					unpriced++;
				} else {
					marketValue = marketValue.add(converted.get());
				}
			}
		}
			return new InvestmentOverviewResult(baseCurrency, money(brokerCash, baseCurrency), money(marketValue, baseCurrency),
				money(brokerCash.add(marketValue), baseCurrency), unpriced, stale);
	}

	@Override
	public InvestmentDashboardSnapshot getDashboard(UUID userId, Instant asOf) {
		InvestmentOverviewResult overview = overview(userId, asOf);
		return new InvestmentDashboardSnapshot(
			overview.baseCurrency(), overview.brokerCash(), overview.positionMarketValue(), overview.unpricedInstrumentCount(),
			overview.staleMarketDataCount());
	}

	public InvestmentReturnCalendarResult returnCalendar(UUID userId, YearMonth month, String scopeType, UUID instrumentId) {
		validateScope(scopeType, instrumentId);
		if (month == null) {
			throw new InvestmentRequestValidationException("收益月份不能为空。");
		}
		ZoneId zone = timezones.currentTimezone(userId);
		String baseCurrency = baseCurrencies.currentBaseCurrency(userId);
		PublishedCalendar published = ensureCalendar(userId, month, scopeType, instrumentId, baseCurrency, zone);
		List<InvestmentReturnCalendarResult.InvestmentReturnDayResult> days = new ArrayList<>();
		for (DayComputation computation : published.days()) {
			days.add(new InvestmentReturnCalendarResult.InvestmentReturnDayResult(
				computation.businessDate(), computation.status(), computation.dailyProfit(), computation.dailyReturnRate(),
				computation.missingInstrumentCount()));
		}
		String summaryStatus = summaryStatus(days);
		BigDecimal monthlyProfit = null;
		BigDecimal monthlyReturnRate = null;
		int profitDays = 0;
		int lossDays = 0;
		int zeroDays = 0;
		BigDecimal linked = BigDecimal.ONE;
		boolean linkedAvailable = true;
		for (var day : days) {
			if (day.status() != ReturnStatus.CALCULATED) {
				continue;
			}
			if (day.dailyProfit().signum() > 0) profitDays++;
			else if (day.dailyProfit().signum() < 0) lossDays++;
			else zeroDays++;
			monthlyProfit = (monthlyProfit == null ? BigDecimal.ZERO : monthlyProfit).add(day.dailyProfit());
			if (day.dailyReturnRate() == null) {
				linkedAvailable = false;
			} else {
				linked = linked.multiply(BigDecimal.ONE.add(day.dailyReturnRate()));
			}
		}
		if (summaryStatus.equals("COMPLETE") && monthlyProfit != null && linkedAvailable) {
			monthlyReturnRate = linked.subtract(BigDecimal.ONE);
		} else {
			monthlyProfit = null;
		}
		List<String> warnings = days.stream().filter(day -> day.missingInstrumentCount() > 0)
			.map(day -> "UNPRICED_INSTRUMENTS").distinct().toList();
		return new InvestmentReturnCalendarResult(
			scopeType, instrumentId, baseCurrency, month, published.revision(),
			month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant(), published.recalculatedAt(), summaryStatus,
			monthlyProfit, monthlyReturnRate, profitDays, lossDays, zeroDays, days, warnings);
	}

	public InvestmentReturnDayDetailsResult returnDayDetails(
		UUID userId, LocalDate day, String scopeType, UUID instrumentId) {
		validateScope(scopeType, instrumentId);
		if (day == null) {
			throw new InvestmentRequestValidationException("收益日期不能为空。");
		}
		ZoneId zone = timezones.currentTimezone(userId);
		String baseCurrency = baseCurrencies.currentBaseCurrency(userId);
		PublishedCalendar published = ensureCalendar(userId, YearMonth.from(day), scopeType, instrumentId, baseCurrency, zone);
		DayComputation computation = published.days().stream()
			.filter(item -> item.businessDate().equals(day)).findFirst()
			.orElseThrow(() -> new InvestmentPersistenceException(new IllegalStateException("收益日历日期快照缺失。")));
		List<InvestmentReturnDayDetailsResult.Contribution> contributions = new ArrayList<>();
		for (InstrumentMovement movement : computation.instrumentMovements().values()) {
			BigDecimal profit = computation.status() == ReturnStatus.CALCULATED ? movement.profit() : null;
			BigDecimal rate = movement.beginValue().signum() > 0 && profit != null
				? profit.divide(movement.beginValue(), 24, RoundingMode.HALF_UP) : null;
			contributions.add(new InvestmentReturnDayDetailsResult.Contribution(
				"INSTRUMENT", movement.instrumentId(), movement.label(), profit, rate,
				computation.status(), movement.priceAsOf()));
		}
		BigDecimal contributionTotal = contributions.stream().map(InvestmentReturnDayDetailsResult.Contribution::profit)
			.filter(Objects::nonNull).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (computation.status() == ReturnStatus.CALCULATED && computation.dailyProfit() != null
				&& computation.dailyProfit().subtract(contributionTotal).compareTo(BigDecimal.ZERO) != 0) {
			contributions.add(new InvestmentReturnDayDetailsResult.Contribution(
				"BROKER_CASH", null, "券商现金", computation.dailyProfit().subtract(contributionTotal), null,
				ReturnStatus.CALCULATED, day));
		}
		return new InvestmentReturnDayDetailsResult(
			scopeType, instrumentId, day, baseCurrency, published.revision(), day.plusDays(1).atStartOfDay(zone).toInstant(), computation.status(),
			computation.beginValue(), computation.endValue(), computation.netCashFlow(), computation.dailyProfit(), computation.dailyReturnRate(),
			computation.dailyProfit(), BigDecimal.ZERO, computation.dividends(), computation.fees(), computation.taxes(), contributions,
			computation.warnings());
	}

	private PublishedCalendar ensureCalendar(
		UUID userId,
		YearMonth month,
		String scopeType,
		UUID instrumentId,
		String baseCurrency,
		ZoneId zone) {
		return transactions.required(() -> {
			List<DayComputation> days = new ArrayList<>();
			List<InvestmentValuationRevisionPort.DailySnapshot> snapshots = new ArrayList<>();
			for (LocalDate day = month.atDay(1); !day.isAfter(month.atEndOfMonth()); day = day.plusDays(1)) {
				DayComputation computation = calculateDay(userId, month, day, scopeType, instrumentId, baseCurrency, zone);
				DayComputation normalized = normalize(computation);
				days.add(normalized);
				snapshots.add(new InvestmentValuationRevisionPort.DailySnapshot(
					normalized.businessDate(), normalized.status(), normalized.beginValue(), normalized.endValue(), normalized.netCashFlow(),
					normalized.dailyProfit(), normalized.dailyReturnRate(), normalized.missingInstrumentCount()));
			}
			InvestmentValuationRevisionPort.Publication publication = valuationRevisions.publish(
				userId, scopeType, instrumentId, baseCurrency, month, snapshots, clock.instant());
			return new PublishedCalendar(days, publication.revision(), publication.recalculatedAt());
		});
	}

	private DayComputation calculateDay(
		UUID userId,
		YearMonth month,
		LocalDate day,
		String scopeType,
		UUID instrumentId,
		String baseCurrency,
		ZoneId zone) {
		Instant start = day.atStartOfDay(zone).toInstant();
		Instant end = day.plusDays(1).atStartOfDay(zone).toInstant();
		Instant previousEnd = start.minusNanos(1);
		List<AccountView> accounts = includedInvestmentAccountsAt(userId, start);
		BigDecimal beginValue = BigDecimal.ZERO;
		BigDecimal endValue = BigDecimal.ZERO;
		BigDecimal netCashFlow = BigDecimal.ZERO;
		int missing = 0;
		boolean hasAsset = false;
		boolean event = false;
		Map<UUID, InstrumentMovement> movements = new LinkedHashMap<>();
		Set<UUID> missingInstruments = new LinkedHashSet<>();
		List<ModifiedDietzCalculator.CashFlow> portfolioFlows = new ArrayList<>();
		List<ModifiedDietzCalculator.CashFlow> instrumentFlows = new ArrayList<>();
		BigDecimal dividends = BigDecimal.ZERO;
		BigDecimal fees = BigDecimal.ZERO;
		BigDecimal taxes = BigDecimal.ZERO;
		for (AccountView account : accounts) {
			List<InvestmentTrade> dayTrades = facts.listTrades(account.id(), end, day, day);
			if (!dayTrades.isEmpty()) {
				event = true;
			}
			if ("PORTFOLIO".equals(scopeType)) {
				Optional<BigDecimal> beginCash = convert(cashBalances.findCashBalance(account.id(), previousEnd).amount(), account.currency(), baseCurrency, previousEnd);
				Optional<BigDecimal> endCash = convert(cashBalances.findCashBalance(account.id(), end).amount(), account.currency(), baseCurrency, end);
				if (beginCash.isPresent() && endCash.isPresent()) {
					beginValue = beginValue.add(beginCash.get().multiply(account.inclusionRatio()));
					endValue = endValue.add(endCash.get().multiply(account.inclusionRatio()));
					// PORTFOLIO 的投资资产包含券商现金；仅有现金且无持仓时也不能误判为非交易日。
					hasAsset = hasAsset || beginCash.get().signum() != 0 || endCash.get().signum() != 0;
				} else {
					missing++;
				}
				List<InvestmentExternalCashFlowPort.CashFlow> flows = externalCashFlows.list(account.id(), start, end);
				if (!flows.isEmpty()) {
					event = true;
				}
				for (InvestmentExternalCashFlowPort.CashFlow flow : flows) {
					Optional<BigDecimal> converted = convert(flow.amount(), account.currency(), baseCurrency, flow.occurredAt())
						.map(value -> value.multiply(account.inclusionRatio()));
					if (converted.isPresent()) {
						netCashFlow = netCashFlow.add(converted.get());
						portfolioFlows.add(new ModifiedDietzCalculator.CashFlow(flow.occurredAt(), converted.get()));
					} else {
						missing++;
					}
				}
			}
			Set<UUID> instrumentIds = new LinkedHashSet<>();
			for (InvestmentTrade trade : facts.listTrades(account.id(), end, null, null)) {
				if (trade.tradeAt().isBefore(end)) {
					instrumentIds.add(trade.instrumentId());
				}
			}
			if ("INSTRUMENT".equals(scopeType)) {
				instrumentIds = Set.of(instrumentId);
			}
			for (UUID currentInstrumentId : instrumentIds) {
				Position beginPosition = positionAt(account.id(), currentInstrumentId, previousEnd);
				Position endPosition = positionAt(account.id(), currentInstrumentId, end);
				if (beginPosition.quantity().signum() > 0 || endPosition.quantity().signum() > 0) {
					hasAsset = true;
				}
				InvestmentFactReadPort.InstrumentSnapshot instrument = facts.findInstrument(currentInstrumentId).orElse(null);
				if (instrument == null) {
					missingInstruments.add(currentInstrumentId);
					continue;
				}
				Optional<InvestmentMarketDataPort.PriceSnapshot> beginPrice = price(currentInstrumentId, previousEnd, instrument.currency(), zone);
				Optional<InvestmentMarketDataPort.PriceSnapshot> endPrice = price(currentInstrumentId, end, instrument.currency(), zone);
				Optional<BigDecimal> beginMarket = marketValue(beginPosition, beginPrice, instrument.currency(), baseCurrency, previousEnd);
				Optional<BigDecimal> endMarket = marketValue(endPosition, endPrice, instrument.currency(), baseCurrency, end);
				if (beginPosition.quantity().signum() > 0 && beginMarket.isEmpty()
					|| endPosition.quantity().signum() > 0 && endMarket.isEmpty()) {
					missingInstruments.add(currentInstrumentId);
					continue;
				}
					BigDecimal begin = beginMarket.orElse(BigDecimal.ZERO).multiply(account.inclusionRatio());
					BigDecimal finish = endMarket.orElse(BigDecimal.ZERO).multiply(account.inclusionRatio());
					beginValue = beginValue.add(begin);
					endValue = endValue.add(finish);
					BigDecimal instrumentFlow = BigDecimal.ZERO;
					boolean missingFlow = false;
					for (InvestmentTrade trade : dayTrades.stream().filter(item -> item.instrumentId().equals(currentInstrumentId)).toList()) {
						event = true;
						Optional<BigDecimal> convertedFlow = convert(instrumentCashFlow(trade), account.currency(), baseCurrency, trade.tradeAt())
							.map(value -> value.multiply(account.inclusionRatio()));
						if (convertedFlow.isEmpty()) {
							missingFlow = true;
							continue;
						}
						instrumentFlow = instrumentFlow.add(convertedFlow.get());
						instrumentFlows.add(new ModifiedDietzCalculator.CashFlow(trade.tradeAt(), convertedFlow.get()));
						Optional<BigDecimal> dividend = convert(trade.grossAmount(), account.currency(), baseCurrency, trade.tradeAt())
							.map(value -> value.multiply(account.inclusionRatio()));
						Optional<BigDecimal> fee = convert(trade.feeAmount(), account.currency(), baseCurrency, trade.tradeAt())
							.map(value -> value.multiply(account.inclusionRatio()));
						Optional<BigDecimal> tax = convert(trade.taxAmount(), account.currency(), baseCurrency, trade.tradeAt())
							.map(value -> value.multiply(account.inclusionRatio()));
						if (dividend.isEmpty() || fee.isEmpty() || tax.isEmpty()) {
							missingFlow = true;
							continue;
						}
						dividends = dividends.add(trade.side() == InvestmentSide.DIVIDEND ? dividend.get() : BigDecimal.ZERO);
						fees = fees.add(fee.get());
						taxes = taxes.add(tax.get());
					}
					if (missingFlow) {
						missingInstruments.add(currentInstrumentId);
						continue;
					}
				if ("INSTRUMENT".equals(scopeType)) {
					netCashFlow = netCashFlow.add(instrumentFlow);
				}
				BigDecimal profit = finish.subtract(begin).subtract(instrumentFlow);
				movements.merge(currentInstrumentId,
					new InstrumentMovement(currentInstrumentId, instrument.name(), begin, finish, instrumentFlow, profit,
						endPrice.map(InvestmentMarketDataPort.PriceSnapshot::businessDate).orElse(null)), InstrumentMovement::add);
			}
		}
		missing = Math.max(missing, missingInstruments.size());
		if ("PORTFOLIO".equals(scopeType)) {
			for (ModifiedDietzCalculator.CashFlow flow : portfolioFlows) {
				if (flow.amount().signum() != 0) {
					event = true;
				}
			}
		}
		ReturnStatus status;
		if (missing > 0) {
			status = "INSTRUMENT".equals(scopeType) ? ReturnStatus.UNPRICED : (hasAsset ? ReturnStatus.PARTIAL : ReturnStatus.UNPRICED);
		} else if (!hasAsset && !event) {
			status = ReturnStatus.NON_TRADING_DAY;
		} else if (!hasAsset) {
			status = ReturnStatus.NO_POSITION;
		} else {
			status = ReturnStatus.CALCULATED;
		}
		BigDecimal dailyProfit = null;
		BigDecimal dailyReturnRate = null;
		if (status == ReturnStatus.CALCULATED) {
			List<ModifiedDietzCalculator.CashFlow> flows = "PORTFOLIO".equals(scopeType)
				? portfolioFlows
				: instrumentFlows;
			ModifiedDietzCalculator.Result result = modifiedDietz.calculate(beginValue, endValue, start, end, flows);
			dailyProfit = result.profit();
			dailyReturnRate = result.returnRate();
		}
		List<String> warnings = new ArrayList<>();
		if (missing > 0) {
			warnings.add("UNPRICED_INSTRUMENTS");
		}
			return new DayComputation(day, status, beginValue, endValue, netCashFlow, dailyProfit, dailyReturnRate, missing,
				dividends, fees, taxes, movements, warnings);
	}

	private InvestmentPositionResult positionResult(Position position, String currency, Instant asOf, ZoneId zone) {
		InvestmentFactReadPort.InstrumentSnapshot instrument = facts.findInstrument(position.instrumentId())
			.orElseThrow(InvestmentNotVisibleException::new);
		Optional<InvestmentMarketDataPort.PriceSnapshot> price = price(position.instrumentId(), asOf, instrument.currency(), zone);
		if (price.isEmpty()) {
			return new InvestmentPositionResult(position.instrumentId(), quantity(position.quantity()), money(position.costBasis(), currency),
				quantity(position.averageCost()), ValuationStatus.UNPRICED, null, null, null, null, currency, null);
		}
		BigDecimal marketValue = round(position.quantity().multiply(price.get().price()), CurrencyCode.fromCode(currency));
		return new InvestmentPositionResult(position.instrumentId(), quantity(position.quantity()), money(position.costBasis(), currency),
			quantity(position.averageCost()), ValuationStatus.PRICED, price.get().price(), marketValue,
			money(marketValue.subtract(position.costBasis()), currency), price.get().businessDate(), currency, price.get().freshness());
	}

	private PerformanceParts performanceParts(List<InvestmentTrade> trades, String currency, Instant asOf, ZoneId zone) {
		Map<UUID, Position> current = new LinkedHashMap<>();
		BigDecimal realized = BigDecimal.ZERO;
		BigDecimal dividends = BigDecimal.ZERO;
		BigDecimal fees = BigDecimal.ZERO;
		BigDecimal taxes = BigDecimal.ZERO;
		BigDecimal nonSellFees = BigDecimal.ZERO;
		BigDecimal nonSellTaxes = BigDecimal.ZERO;
		BigDecimal invested = BigDecimal.ZERO;
		for (InvestmentTrade trade : trades.stream().sorted(Comparator.comparing(InvestmentTrade::tradeAt).thenComparing(InvestmentTrade::id)).toList()) {
			Position before = current.getOrDefault(trade.instrumentId(), Position.empty(trade.instrumentId()));
			PositionCalculator.AppliedTrade applied = positions.apply(before, trade);
			current.put(trade.instrumentId(), applied.remaining());
			fees = fees.add(trade.feeAmount());
			taxes = taxes.add(trade.taxAmount());
			if (trade.side() == InvestmentSide.SELL) {
				realized = realized.add(trade.grossAmount().subtract(applied.releasedCost()).subtract(trade.feeAmount()).subtract(trade.taxAmount()));
				invested = invested.add(trade.grossAmount());
			} else if (trade.side() == InvestmentSide.BUY) {
				invested = invested.add(trade.grossAmount());
				nonSellFees = nonSellFees.add(trade.feeAmount());
				nonSellTaxes = nonSellTaxes.add(trade.taxAmount());
			} else {
				dividends = dividends.add(trade.grossAmount());
				nonSellFees = nonSellFees.add(trade.feeAmount());
				nonSellTaxes = nonSellTaxes.add(trade.taxAmount());
			}
		}
		BigDecimal marketValue = BigDecimal.ZERO;
		BigDecimal unrealized = BigDecimal.ZERO;
		boolean unpriced = false;
		for (Position position : current.values()) {
			if (position.quantity().signum() == 0) {
				continue;
			}
			InvestmentFactReadPort.InstrumentSnapshot instrument = facts.findInstrument(position.instrumentId()).orElse(null);
			Optional<InvestmentMarketDataPort.PriceSnapshot> price = instrument == null
				? Optional.empty() : this.price(position.instrumentId(), asOf, instrument.currency(), zone);
			if (price.isEmpty()) {
				unpriced = true;
				continue;
			}
			BigDecimal value = position.quantity().multiply(price.get().price());
			marketValue = marketValue.add(value);
			unrealized = unrealized.add(value.subtract(position.costBasis()));
		}
		return new PerformanceParts(realized, unrealized, dividends, fees, taxes, nonSellFees, nonSellTaxes, invested,
			marketValue, unpriced);
	}

	private Position positionBefore(UUID accountId, UUID instrumentId, Instant tradeAt) {
		List<InvestmentTrade> history = facts.listTrades(accountId, tradeAt.minusNanos(1), null, null).stream()
			.filter(trade -> trade.instrumentId().equals(instrumentId)).toList();
		return positions.rebuild(history).getOrDefault(instrumentId, Position.empty(instrumentId));
	}

	private Position positionAt(UUID accountId, UUID instrumentId, Instant asOf) {
		List<InvestmentTrade> history = facts.listTrades(accountId, asOf, null, null).stream()
			.filter(trade -> trade.instrumentId().equals(instrumentId)).toList();
		return positions.rebuild(history).getOrDefault(instrumentId, Position.empty(instrumentId));
	}

	private Map<UUID, Position> rebuildPositions(UUID accountId, Instant asOf) {
		return positions.rebuild(facts.listTrades(accountId, asOf, null, null));
	}

	private Optional<InvestmentMarketDataPort.PriceSnapshot> price(
		UUID instrumentId, Instant asOf, String currency, ZoneId zone) {
		// 行情业务日按用户时区归属，避免 UTC 日界把亚洲用户的历史价格错移一天。
		LocalDate businessDate = asOf.atZone(zone).toLocalDate();
		return marketData.latestPrice(instrumentId, businessDate, currency);
	}

	private Optional<BigDecimal> marketValue(
		Position position,
		Optional<InvestmentMarketDataPort.PriceSnapshot> price,
		String fromCurrency,
		String toCurrency,
		Instant asOf) {
		if (position.quantity().signum() == 0) {
			return Optional.of(BigDecimal.ZERO);
		}
		if (price.isEmpty()) {
			return Optional.empty();
		}
		return convert(position.quantity().multiply(price.get().price()), fromCurrency, toCurrency, asOf);
	}

	private Optional<BigDecimal> convert(BigDecimal amount, String from, String to, Instant asOf) {
		if (from.equals(to)) {
			return Optional.of(amount);
		}
		return exchangeRates.rate(from, to, asOf).map(amount::multiply);
	}

	private AccountView requireReadableInvestmentAccount(UUID userId, UUID accountId) {
		if (userId == null || accountId == null || memberships.findActiveMembership(userId, accountId).isEmpty()) {
			throw new InvestmentNotVisibleException();
		}
		AccountPostingReference account = accounts.findById(accountId).orElseThrow(InvestmentNotVisibleException::new);
		if (!"INVESTMENT".equals(account.accountClass())) {
			throw new InvestmentBusinessRuleException("目标账户不是投资账户。");
		}
			return new AccountView(account.id(), account.currency(), BigDecimal.ONE);
	}

	private AccountPostingReference requireWritableInvestmentAccount(UUID userId, UUID accountId) {
		AccountPostingReference account = accounts.findById(accountId).orElseThrow(InvestmentNotVisibleException::new);
		switch (postingAccess.postingDecision(userId, accountId)) {
			case ALLOWED -> {
				if (!account.active() || !"INVESTMENT".equals(account.accountClass())) {
					throw new InvestmentBusinessRuleException("目标账户不是可写投资账户。");
				}
				return account;
			}
			case READ_ONLY -> throw new InvestmentPermissionDeniedException();
			default -> throw new InvestmentNotVisibleException();
		}
	}

	private List<AccountView> visibleInvestmentAccounts(UUID userId, boolean includedOnly) {
		List<AccountView> result = new ArrayList<>();
		for (AccountMembershipReadPort.ActiveMembership membership : memberships.listActiveMemberships(userId)) {
			if (includedOnly && membership.inclusionRatio().signum() <= 0) {
				continue;
			}
			accounts.findById(membership.accountId()).filter(account -> "INVESTMENT".equals(account.accountClass()))
				.ifPresent(account -> result.add(new AccountView(account.id(), account.currency(), membership.inclusionRatio())));
		}
		return result;
	}

	private static BigDecimal grossAmount(InvestmentTradeCommand command, CurrencyCode currency) {
		if (command.side() == InvestmentSide.DIVIDEND) {
			return round(command.dividendAmount(), currency);
		}
		return round(command.quantity().multiply(command.unitPrice()), currency);
	}

	private static BigDecimal round(BigDecimal value, CurrencyCode currency) {
		return value.setScale(currency.minorUnits(), RoundingMode.HALF_UP);
	}

	private static BigDecimal money(BigDecimal value, String currency) {
		return round(value, CurrencyCode.fromCode(currency));
	}

	private static DayComputation normalize(DayComputation computation) {
		Map<UUID, InstrumentMovement> movements = new LinkedHashMap<>();
		for (InstrumentMovement movement : computation.instrumentMovements().values()) {
			movements.put(movement.instrumentId(), new InstrumentMovement(
				movement.instrumentId(), movement.label(), scaleMoney(movement.beginValue()), scaleMoney(movement.endValue()),
				scaleMoney(movement.cashFlow()), scaleMoney(movement.profit()), movement.priceAsOf()));
		}
		// 对外日历和持久化投影共用同一精度，避免同一 revision 返回另一份未持久化的高精度值。
		return new DayComputation(computation.businessDate(), computation.status(), scaleMoney(computation.beginValue()),
			scaleMoney(computation.endValue()), scaleMoney(computation.netCashFlow()), scaleMoney(computation.dailyProfit()),
			scaleRate(computation.dailyReturnRate()), computation.missingInstrumentCount(), scaleMoney(computation.dividends()),
			scaleMoney(computation.fees()), scaleMoney(computation.taxes()), movements, computation.warnings());
	}

	private static BigDecimal scaleMoney(BigDecimal value) {
		return value == null ? null : value.setScale(8, RoundingMode.HALF_UP);
	}

	private static BigDecimal scaleRate(BigDecimal value) {
		return value == null ? null : value.setScale(10, RoundingMode.HALF_UP);
	}
	private static BigDecimal quantity(BigDecimal value) {
		return value.setScale(Math.min(12, Math.max(0, value.scale())), RoundingMode.HALF_UP);
	}

	private static InvestmentTradeResult tradeResult(InvestmentTrade trade) {
		return new InvestmentTradeResult(trade.id(), trade.transactionId(), trade.investmentAccountId(), trade.instrumentId(),
			trade.side(), trade.quantity(), trade.unitPrice(), trade.currency(), trade.grossAmount(), trade.feeAmount(),
			trade.taxAmount(), trade.tradeAt());
	}

	private static void validateScope(String scopeType, UUID instrumentId) {
		if (!"PORTFOLIO".equals(scopeType) && !"INSTRUMENT".equals(scopeType)) {
			throw new InvestmentRequestValidationException("收益范围无效。");
		}
		if ("PORTFOLIO".equals(scopeType) && instrumentId != null || "INSTRUMENT".equals(scopeType) && instrumentId == null) {
			throw new InvestmentRequestValidationException("收益范围与产品 ID 不匹配。");
		}
	}

	private static String summaryStatus(List<InvestmentReturnCalendarResult.InvestmentReturnDayResult> days) {
		if (days.stream().anyMatch(day -> day.status() == ReturnStatus.UNPRICED)) return "UNAVAILABLE";
		if (days.stream().anyMatch(day -> day.status() == ReturnStatus.PARTIAL)) return "PARTIAL";
		if (days.stream().anyMatch(day -> day.status() == ReturnStatus.PENDING_DATA)) return "PENDING";
		return days.stream().anyMatch(day -> day.status() == ReturnStatus.CALCULATED) ? "COMPLETE" : "UNAVAILABLE";
	}

	private static BigDecimal instrumentCashFlow(InvestmentTrade trade) {
		return switch (trade.side()) {
			case BUY -> trade.grossAmount().add(trade.feeAmount()).add(trade.taxAmount());
			case SELL -> trade.grossAmount().subtract(trade.feeAmount()).subtract(trade.taxAmount()).negate();
			case DIVIDEND -> trade.grossAmount().negate();
		};
	}

	private List<AccountView> includedInvestmentAccountsAt(UUID userId, Instant businessAt) {
		List<AccountView> result = new ArrayList<>();
		for (AccountInclusionReadPort.MembershipInclusion inclusion : inclusions.listIncludedAt(userId, businessAt)) {
			accounts.findById(inclusion.accountId()).filter(account -> "INVESTMENT".equals(account.accountClass()))
				.ifPresent(account -> result.add(new AccountView(account.id(), account.currency(), inclusion.ratio())));
		}
		return result;
	}

	private record AccountView(UUID id, String currency, BigDecimal inclusionRatio) {
	}

	public record InvestmentOverviewResult(
		String baseCurrency, BigDecimal brokerCash, BigDecimal positionMarketValue, BigDecimal totalInvestmentAssets,
		int unpricedInstrumentCount, int staleMarketDataCount) {

		public InvestmentOverviewResult(
			String baseCurrency, BigDecimal brokerCash, BigDecimal positionMarketValue, BigDecimal totalInvestmentAssets,
			int unpricedInstrumentCount) {
			this(baseCurrency, brokerCash, positionMarketValue, totalInvestmentAssets, unpricedInstrumentCount, 0);
		}
	}

	private record PerformanceParts(
		BigDecimal realizedProfit, BigDecimal unrealizedProfit, BigDecimal dividends, BigDecimal fees, BigDecimal taxes,
		BigDecimal nonSellFees, BigDecimal nonSellTaxes, BigDecimal investedCapital, BigDecimal unrealizedMarketValue,
		boolean unpriced) {
	}

	private record InstrumentMovement(
		UUID instrumentId, String label, BigDecimal beginValue, BigDecimal endValue, BigDecimal cashFlow,
		BigDecimal profit, LocalDate priceAsOf) {

		private InstrumentMovement add(InstrumentMovement other) {
			return new InstrumentMovement(instrumentId, label, beginValue.add(other.beginValue), endValue.add(other.endValue),
				cashFlow.add(other.cashFlow), profit.add(other.profit), priceAsOf == null ? other.priceAsOf : priceAsOf);
		}
	}

	private record DayComputation(
		LocalDate businessDate, ReturnStatus status, BigDecimal beginValue, BigDecimal endValue, BigDecimal netCashFlow, BigDecimal dailyProfit,
		BigDecimal dailyReturnRate, int missingInstrumentCount, BigDecimal dividends, BigDecimal fees, BigDecimal taxes,
		Map<UUID, InstrumentMovement> instrumentMovements, List<String> warnings) {
	}

	private record PublishedCalendar(List<DayComputation> days, int revision, Instant recalculatedAt) {
		private PublishedCalendar {
			days = List.copyOf(days);
		}
	}
}
