package com.arextest.schedule.service;

import com.arextest.common.config.DefaultApplicationConfig;
import com.arextest.schedule.bizlog.BizLogger;
import com.arextest.schedule.common.CommonConstant;
import com.arextest.schedule.common.SendSemaphoreLimiter;
import com.arextest.schedule.comparer.ComparisonWriter;
import com.arextest.schedule.comparer.ReplayResultComparer;
import com.arextest.schedule.dao.mongodb.ReplayActionCaseItemRepository;
import com.arextest.schedule.mdc.MDCTracer;
import com.arextest.schedule.model.CaseSendStatusType;
import com.arextest.schedule.model.ExecutionStatus;
import com.arextest.schedule.model.PlanExecutionContext;
import com.arextest.schedule.model.ReplayActionCaseItem;
import com.arextest.schedule.model.ReplayActionItem;
import com.arextest.schedule.model.ReplayPlan;
import com.arextest.schedule.progress.ProgressEvent;
import com.arextest.schedule.progress.ProgressTracer;
import com.arextest.schedule.sender.ReplaySender;
import com.arextest.schedule.sender.ReplaySenderFactory;
import com.arextest.schedule.service.noise.ReplayNoiseIdentify;
import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * @author jmo
 * @since 2021/9/13
 */
@Service
@Slf4j
public class ReplayCaseTransmitServiceImpl implements ReplayCaseTransmitService {

  private static final String COMPARE_DELAY_SECONDS = "compare.delay.seconds";
  private static final int DEFAULT_COMPARE_DELAY_SECONDS = 60;
  @Resource
  private ExecutorService sendExecutorService;
  @Resource
  private ExecutorService compareExecutorService;
  @Resource
  private ReplayResultComparer replayResultComparer;
  @Resource
  private ScheduledExecutorService compareScheduleExecutorService;
  @Resource
  private ReplayCompareService replayCompareService;
  @Resource
  private ReplayActionCaseItemRepository replayActionCaseItemRepository;
  @Resource
  private ProgressTracer progressTracer;
  @Resource
  private ReplaySenderFactory senderFactory;
  @Resource
  private ComparisonWriter comparisonWriter;
  @Resource
  private ProgressEvent progressEvent;
  @Resource
  private MetricService metricService;
  @Resource
  private ReplayNoiseIdentify replayNoiseIdentify;
  @Resource
  private DefaultApplicationConfig defaultConfig;

  public void send(List<ReplayActionCaseItem> caseItems, PlanExecutionContext<?> executionContext) {
    ExecutionStatus executionStatus = executionContext.getExecutionStatus();

    if (executionStatus.isAbnormal()) {
      return;
    }

    prepareActionItems(caseItems);

    replayNoiseIdentify.noiseIdentify(caseItems, executionContext);

    try {
      doSendValuesToRemoteHost(caseItems, executionStatus);
    } catch (Throwable throwable) {
      LOGGER.error("do send error:{}", throwable.getMessage(), throwable);
      markAllSendStatus(caseItems, CaseSendStatusType.EXCEPTION_FAILED);
    }
  }

  private void prepareActionItems(List<ReplayActionCaseItem> caseItems) {
    Map<ReplayActionItem, List<ReplayActionCaseItem>> actionsOfBatch = caseItems.stream()
        .filter(caseItem -> caseItem.getParent() != null)
        .collect(Collectors.groupingBy(ReplayActionCaseItem::getParent));

    actionsOfBatch.forEach((actionItem, casesOfAction) -> {
      // warmUp should be done once for each endpoint
      if (!actionItem.isItemProcessed()) {
        actionItem.setItemProcessed(true);
        progressEvent.onActionBeforeSend(actionItem);
      }
    });
  }

  public void releaseCasesOfContext(ReplayPlan replayPlan,
      PlanExecutionContext<?> executionContext) {
    // checkpoint: before skipping batch of group
    if (executionContext.getExecutionStatus().isAbnormal()) {
      return;
    }

    Map<String, Long> caseCountMap = replayActionCaseItemRepository.countWaitHandlingByAction(
        replayPlan.getId(),
        executionContext.getContextCaseQuery());
    Map<String, ReplayActionItem> actionItemMap = replayPlan.getActionItemMap();

    for (Map.Entry<String, Long> actionIdToCaseCount : caseCountMap.entrySet()) {
      ReplayActionItem replayActionItem = actionItemMap.get(actionIdToCaseCount.getKey());
      int contextCasesCount = actionIdToCaseCount.getValue().intValue();
      replayActionItem.recordProcessCaseCount(contextCasesCount);
      replayActionItem.getSendRateLimiter().batchRelease(false, contextCasesCount);

      // if we skip the rest of cases remaining in the action item, set its status
      if (replayActionItem.getReplayCaseCount() == replayActionItem.getCaseProcessCount()
          .intValue()) {
        progressTracer.finishCaseByPlan(replayPlan, contextCasesCount);
      } else {
        progressTracer.finishCaseByAction(replayActionItem, contextCasesCount);
      }
      BizLogger.recordContextSkipped(executionContext, replayActionItem, contextCasesCount);
      LOGGER.info("action item {} skip {} cases", replayActionItem.getId(), contextCasesCount);
    }
  }

  private void doSendValuesToRemoteHost(List<ReplayActionCaseItem> values,
      ExecutionStatus executionStatus) {
    final int valueSize = values.size();
    final SendSemaphoreLimiter semaphore = executionStatus.getLimiter();
    final CountDownLatch groupSentLatch = new CountDownLatch(valueSize);

    for (int i = 0; i < valueSize; i++) {
      ReplayActionCaseItem replayActionCaseItem = values.get(i);
      ReplayActionItem actionItem = replayActionCaseItem.getParent();
      MDCTracer.addDetailId(replayActionCaseItem.getId());
      // checkpoint: before init case runnable
      if (executionStatus.isAbnormal()) {
        LOGGER.info("replay interrupted,case item id:{}", replayActionCaseItem.getId());
        MDCTracer.removeDetailId();
        return;
      }

      try {
        ReplaySender replaySender = findReplaySender(replayActionCaseItem);
        if (replaySender == null) {
          groupSentLatch.countDown();
          doSendFailedAsFinish(replayActionCaseItem, CaseSendStatusType.READY_DEPENDENCY_FAILED);
          continue;
        }
        semaphore.acquire();
        AsyncSendCaseTaskRunnable taskRunnable = new AsyncSendCaseTaskRunnable(this);
        taskRunnable.setExecutionStatus(executionStatus);
        taskRunnable.setCaseItem(replayActionCaseItem);
        taskRunnable.setReplaySender(replaySender);
        taskRunnable.setGroupSentLatch(groupSentLatch);
        taskRunnable.setLimiter(semaphore);
        taskRunnable.setMetricService(metricService);
        sendExecutorService.execute(taskRunnable);
        LOGGER.info("submit replay sending success");
      } catch (Throwable throwable) {
        groupSentLatch.countDown();
        semaphore.release(false);
        replayActionCaseItem.buildParentErrorMessage(throwable.getMessage());
        LOGGER.error("send group to remote host error:{} ,case item id:{}", throwable.getMessage(),
            replayActionCaseItem.getId(), throwable);
        doSendFailedAsFinish(replayActionCaseItem, CaseSendStatusType.EXCEPTION_FAILED);
      } finally {
        actionItem.recordProcessOne();
      }
    }

    try {
      boolean clear = groupSentLatch.await(CommonConstant.GROUP_SENT_WAIT_TIMEOUT_SECONDS,
          TimeUnit.SECONDS);
      if (!clear) {
        LOGGER.error("Send group failed to await all request of batch");
      }
    } catch (InterruptedException e) {
      LOGGER.error("send group to remote host error:{}", e.getMessage(), e);
      Thread.currentThread().interrupt();
    }
    MDCTracer.removeDetailId();
  }

  @Override
  public void updateSendResult(ReplayActionCaseItem caseItem, CaseSendStatusType sendStatusType) {
    if (caseItem.getSourceResultId() == null) {
      caseItem.setSourceResultId(StringUtils.EMPTY);
    }
    if (caseItem.getTargetResultId() == null) {
      caseItem.setTargetResultId(StringUtils.EMPTY);
    }
    caseItem.setSendStatus(sendStatusType.getValue());

    if (sendStatusType == CaseSendStatusType.SUCCESS) {
      replayActionCaseItemRepository.updateSendResult(caseItem);
      // async compare task
      int compareDelaySeconds = defaultConfig.getConfigAsInt(
          COMPARE_DELAY_SECONDS, DEFAULT_COMPARE_DELAY_SECONDS);
      if (compareDelaySeconds == 0) {
        AsyncCompareCaseTaskRunnable compareTask = new AsyncCompareCaseTaskRunnable(
            replayResultComparer, caseItem);
        compareExecutorService.execute(compareTask);
      } else {
        AsyncDelayCompareCaseTaskRunnable delayCompareTask = new AsyncDelayCompareCaseTaskRunnable(
            replayCompareService, caseItem);
        compareScheduleExecutorService.schedule(delayCompareTask,
            compareDelaySeconds, TimeUnit.SECONDS);
      }
      LOGGER.info("Async compare task distributed, case id: {}, recordId: {}", caseItem.getId(),
          caseItem.getRecordId());
    } else {
      doSendFailedAsFinish(caseItem, sendStatusType);
    }
  }

  private ReplaySender findReplaySender(ReplayActionCaseItem caseItem) {
    ReplaySender sender = senderFactory.findReplaySender(caseItem.getCaseType());
    if (sender != null) {
      return sender;
    }
    LOGGER.warn("find empty ReplaySender for detailId: {}, caseType:{}", caseItem.getId(),
        caseItem.getCaseType());
    return null;
  }

  private void markAllSendStatus(List<ReplayActionCaseItem> sourceItemList,
      CaseSendStatusType sendStatusType) {
    for (ReplayActionCaseItem caseItem : sourceItemList) {
      doSendFailedAsFinish(caseItem, sendStatusType);
    }
  }

  @Override
  public void doSendFailedAsFinish(ReplayActionCaseItem caseItem,
      CaseSendStatusType sendStatusType) {
    try {
      LOGGER.info("try do send failed as finish case id: {} ,send status:{}", caseItem.getId(),
          sendStatusType);
      caseItem.setSendStatus(sendStatusType.getValue());
      if (caseItem.getSourceResultId() == null) {
        caseItem.setSourceResultId(StringUtils.EMPTY);
      }
      if (caseItem.getTargetResultId() == null) {
        caseItem.setTargetResultId(StringUtils.EMPTY);
      }
      boolean updateResult = replayActionCaseItemRepository.updateSendResult(caseItem);
      String errorMessage = caseItem.getSendErrorMessage();
      if (StringUtils.isEmpty(errorMessage)) {
        errorMessage = sendStatusType.name();
      }
      comparisonWriter.writeIncomparable(caseItem, errorMessage);
      progressTracer.finishOne(caseItem);
      LOGGER.info("do send failed as finish success case id: {},updateResult:{}", caseItem.getId(),
          updateResult);
    } catch (Throwable throwable) {
      LOGGER.error("doSendFailedAsFinish error:{}", throwable.getMessage(), throwable);
    }

  }
}