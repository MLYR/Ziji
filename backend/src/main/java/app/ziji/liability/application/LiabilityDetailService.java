package app.ziji.liability.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import app.ziji.account.application.LiabilityAccountReference;
import app.ziji.account.application.LiabilityAccountReferencePort;
import app.ziji.accountmember.application.AccountMembershipReadPort;
import app.ziji.accountmember.application.AccountMembershipReadPort.ActiveMembership;
import app.ziji.liability.domain.LiabilityDetail;
import app.ziji.liability.domain.LiabilityDetailException;
import app.ziji.liability.domain.LiabilityDetailPatch;
import app.ziji.liability.domain.LiabilityDetailValues;
import app.ziji.shared.application.IdempotencyExecution;
import app.ziji.shared.application.IdempotencyRequestHasher;
import app.ziji.shared.application.IdempotencyResponse;
import app.ziji.shared.application.IdempotencyWorkResult;
import app.ziji.shared.application.UnifiedIdempotencyService;

/** 负债详情编排：安全前置、详情自身版本与统一幂等均收敛在一个 module interface 后。 */
public class LiabilityDetailService implements LiabilityDetailUseCase {

	private static final String RESOURCE_TYPE = "LIABILITY_DETAIL";

	private final LiabilityAccountReferencePort accounts;
	private final AccountMembershipReadPort memberships;
	private final LiabilityDetailStore details;
	private final UnifiedIdempotencyService idempotency;
	private final Clock clock;

	public LiabilityDetailService(
		LiabilityAccountReferencePort accounts,
		AccountMembershipReadPort memberships,
		LiabilityDetailStore details,
		UnifiedIdempotencyService idempotency,
		Clock clock) {
		if (accounts == null || memberships == null || details == null || idempotency == null || clock == null) {
			throw new LiabilityDetailException.Validation();
		}
		this.accounts = accounts;
		this.memberships = memberships;
		this.details = details;
		this.idempotency = idempotency;
		this.clock = clock;
	}

	@Override
	public LiabilityDetail get(UUID userId, UUID accountId) {
		requireAccess(userId, accountId, false);
		return details.findByAccountId(accountId).orElseGet(() -> LiabilityDetail.empty(accountId));
	}

	@Override
	public void authorizeWrite(UUID userId, UUID accountId) {
		requireAccess(userId, accountId, true);
	}

	@Override
	public LiabilityDetailWriteResult put(
		UUID userId,
		UUID accountId,
		LiabilityDetailPutCondition condition,
		LiabilityDetailValues values,
		String idempotencyKey) {
		if (condition == null || values == null) {
			throw new LiabilityDetailException.Validation();
		}
		Access access = requireAccess(userId, accountId, true);
		values.validateFor(access.account().accountType(), access.account().currency());
		String resource = resource(accountId);
		String hash = IdempotencyRequestHasher.hash(
			"PUT", "application/json", resource, completePayload(values), condition.preconditionValue());
		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, 1, "putLiabilityDetails", idempotencyKey, hash);
		if (inspected.isPresent()) {
			return resolve(inspected.get(), userId, accountId, resource, true);
		}
		preflightPut(accountId, condition);
		IdempotencyExecution<LiabilityDetailWriteResult> execution = idempotency.executeAuthenticated(
			userId, 1, "putLiabilityDetails", idempotencyKey, hash, () -> {
				LiabilityDetail detail = writePut(userId, accountId, condition, values);
				int status = condition.isInitial() ? 201 : 200;
				LiabilityDetailWriteResult result = new LiabilityDetailWriteResult(detail, status);
				return IdempotencyWorkResult.completed(result, succeeded(status, resource, detail));
			});
		return resolve(execution, userId, accountId, resource, true);
	}

	@Override
	public LiabilityDetailWriteResult patch(
		UUID userId,
		UUID accountId,
		int expectedVersion,
		LiabilityDetailPatch patch,
		String idempotencyKey) {
		if (patch == null || expectedVersion < 1) {
			throw new LiabilityDetailException.Validation();
		}
		Access access = requireAccess(userId, accountId, true);
		LiabilityDetail current = details.findByAccountId(accountId)
			.orElseThrow(LiabilityDetailApplicationException.NotFound::new);
		patch.applyTo(current.values()).validateFor(access.account().accountType(), access.account().currency());
		String resource = resource(accountId);
		String hash = IdempotencyRequestHasher.hash(
			"PATCH", "application/merge-patch+json", resource, patchPayload(patch), "\"" + expectedVersion + "\"");
		Optional<IdempotencyExecution<Void>> inspected = idempotency.inspectAuthenticated(
			userId, 1, "patchLiabilityDetails", idempotencyKey, hash);
		if (inspected.isPresent()) {
			return resolve(inspected.get(), userId, accountId, resource, false);
		}
		if (current.version() != expectedVersion) {
			throw new LiabilityDetailApplicationException.VersionConflict(current);
		}
		IdempotencyExecution<LiabilityDetailWriteResult> execution = idempotency.executeAuthenticated(
			userId, 1, "patchLiabilityDetails", idempotencyKey, hash, () -> {
				LiabilityDetail detail = writePatch(userId, accountId, expectedVersion, patch);
				LiabilityDetailWriteResult result = new LiabilityDetailWriteResult(detail, 200);
				return IdempotencyWorkResult.completed(result, succeeded(200, resource, detail));
			});
		return resolve(execution, userId, accountId, resource, false);
	}

	private void preflightPut(UUID accountId, LiabilityDetailPutCondition condition) {
		Optional<LiabilityDetail> current = details.findByAccountId(accountId);
		if (condition.isInitial()) {
			if (current.isPresent()) {
				throw new LiabilityDetailApplicationException.VersionConflict(current.get());
			}
			return;
		}
		LiabilityDetail persisted = current.orElseThrow(LiabilityDetailApplicationException.NotFound::new);
		if (persisted.version() != condition.expectedVersion()) {
			throw new LiabilityDetailApplicationException.VersionConflict(persisted);
		}
	}

	private LiabilityDetail writePut(
		UUID userId,
		UUID accountId,
		LiabilityDetailPutCondition condition,
		LiabilityDetailValues values) {
		Access access = requireAccess(userId, accountId, true);
		values.validateFor(access.account().accountType(), access.account().currency());
		if (condition.isInitial()) {
			LiabilityDetail created = LiabilityDetail.create(accountId, values, clock.instant());
			if (!details.insertIfAbsent(created)) {
				throw new LiabilityDetailApplicationException.VersionConflict(current(accountId));
			}
			return created;
		}
		LiabilityDetail persisted = details.lockByAccountId(accountId)
			.orElseThrow(LiabilityDetailApplicationException.NotFound::new);
		if (persisted.version() != condition.expectedVersion()) {
			throw new LiabilityDetailApplicationException.VersionConflict(persisted);
		}
		LiabilityDetail replacement = persisted.replace(values, clock.instant());
		return details.updateIfVersion(replacement, condition.expectedVersion())
			.orElseThrow(() -> new LiabilityDetailApplicationException.VersionConflict(current(accountId)));
	}

	private LiabilityDetail writePatch(
		UUID userId,
		UUID accountId,
		int expectedVersion,
		LiabilityDetailPatch patch) {
		Access access = requireAccess(userId, accountId, true);
		LiabilityDetail persisted = details.lockByAccountId(accountId)
			.orElseThrow(LiabilityDetailApplicationException.NotFound::new);
		LiabilityDetailValues values = patch.applyTo(persisted.values());
		values.validateFor(access.account().accountType(), access.account().currency());
		if (persisted.version() != expectedVersion) {
			throw new LiabilityDetailApplicationException.VersionConflict(persisted);
		}
		LiabilityDetail replacement = persisted.replace(values, clock.instant());
		return details.updateIfVersion(replacement, expectedVersion)
			.orElseThrow(() -> new LiabilityDetailApplicationException.VersionConflict(current(accountId)));
	}

	private LiabilityDetailWriteResult resolve(
		IdempotencyExecution<?> execution,
		UUID userId,
		UUID accountId,
		String resource,
		boolean put) {
		if (execution.status() == IdempotencyExecution.Status.EXECUTED) {
			if (execution.value() instanceof LiabilityDetailWriteResult result) {
				return result;
			}
			throw new LiabilityDetailApplicationException.SafeReplayUnavailable();
		}
		if (execution.status() == IdempotencyExecution.Status.REPLAYED) {
			return replay(execution.response(), userId, accountId, resource, put);
		}
		if (execution.status() == IdempotencyExecution.Status.KEY_REUSED) {
			throw new LiabilityDetailApplicationException.IdempotencyKeyReused();
		}
		if (execution.status() == IdempotencyExecution.Status.REQUEST_IN_PROGRESS) {
			throw new LiabilityDetailApplicationException.IdempotencyInProgress();
		}
		throw new LiabilityDetailApplicationException.SafeReplayUnavailable();
	}

	private LiabilityDetailWriteResult replay(
		IdempotencyResponse response,
		UUID userId,
		UUID accountId,
		String resource,
		boolean put) {
		if (response == null || response.status() != IdempotencyResponse.Status.SUCCEEDED
			|| !RESOURCE_TYPE.equals(response.resourceType()) || !accountId.equals(response.resourceId())
			|| !(response.reference() instanceof IdempotencyResponse.ResourceReference reference)
			|| reference.resourceVersion() == null || !resource.equals(reference.location())
			|| put && response.responseStatus() != 200 && response.responseStatus() != 201
			|| !put && response.responseStatus() != 200) {
			throw new LiabilityDetailApplicationException.SafeReplayUnavailable();
		}
		requireAccess(userId, accountId, true);
		LiabilityDetail detail = details.findByAccountId(accountId)
			.orElseThrow(LiabilityDetailApplicationException.SafeReplayUnavailable::new);
		if (reference.resourceVersion() != detail.version() || !detail.etag().equals(reference.etag())) {
			// 不保存完整响应体时，只能由同版本不可变快照重建；版本漂移必须 fail closed。
			throw new LiabilityDetailApplicationException.SafeReplayUnavailable();
		}
		return new LiabilityDetailWriteResult(detail, response.responseStatus());
	}

	private Access requireAccess(UUID userId, UUID accountId, boolean write) {
		if (userId == null || accountId == null) {
			throw new LiabilityDetailException.Validation();
		}
		ActiveMembership membership = memberships.findActiveMembership(userId, accountId)
			.orElseThrow(LiabilityDetailApplicationException.NotFound::new);
		LiabilityAccountReference account = accounts.findById(accountId)
			.orElseThrow(LiabilityDetailApplicationException.NotFound::new);
		if (!"LIABILITY".equals(account.accountClass())) {
			throw new LiabilityDetailApplicationException.NotFound();
		}
		if (write && !"OWNER".equals(membership.role()) && !"EDITOR".equals(membership.role())) {
			throw new LiabilityDetailApplicationException.PermissionDenied();
		}
		return new Access(account);
	}

	private LiabilityDetail current(UUID accountId) {
		return details.findByAccountId(accountId)
			.orElseThrow(LiabilityDetailApplicationException.NotFound::new);
	}

	private static IdempotencyResponse succeeded(int status, String resource, LiabilityDetail detail) {
		return IdempotencyResponse.succeededResource(status, RESOURCE_TYPE, detail.accountId(),
			new IdempotencyResponse.ResourceReference(resource, detail.etag(), (long) detail.version()));
	}

	private static String resource(UUID accountId) {
		return "/api/v1/accounts/" + accountId + "/liability-details";
	}

	private static Map<String, Object> completePayload(LiabilityDetailValues values) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("interestRate", decimal(values.interestRate()));
		payload.put("loanDate", values.loanDate());
		payload.put("dueDate", values.dueDate());
		payload.put("billingDay", values.billingDay());
		payload.put("repaymentDay", values.repaymentDay());
		payload.put("currentAmountDue", decimal(values.currentAmountDue()));
		return payload;
	}

	private static Map<String, Object> patchPayload(LiabilityDetailPatch patch) {
		Map<String, Object> payload = new LinkedHashMap<>();
		if (patch.interestRatePresent()) payload.put("interestRate", decimal(patch.interestRate()));
		if (patch.loanDatePresent()) payload.put("loanDate", patch.loanDate());
		if (patch.dueDatePresent()) payload.put("dueDate", patch.dueDate());
		if (patch.billingDayPresent()) payload.put("billingDay", patch.billingDay());
		if (patch.repaymentDayPresent()) payload.put("repaymentDay", patch.repaymentDay());
		if (patch.currentAmountDuePresent()) payload.put("currentAmountDue", decimal(patch.currentAmountDue()));
		return payload;
	}

	private static Object decimal(java.math.BigDecimal value) {
		return value == null ? null : IdempotencyRequestHasher.decimal(value.toPlainString());
	}

	private record Access(LiabilityAccountReference account) {
	}
}
