package com.arextest.schedule.service;

import com.arextest.schedule.dao.mongodb.ReplayBizLogRepository;
import com.arextest.schedule.model.bizlog.BizLog;
import com.arextest.schedule.model.bizlog.ReplayBizLogQueryCondition;
import com.arextest.schedule.model.dao.mongodb.ReplayBizLogCollection;
import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * Created by Qzmo on 2023/6/2
 */
@Service
public class PlanBizLogService {

  @Resource
  private ReplayBizLogRepository replayBizLogRepository;

  public List<ReplayBizLogCollection> queryBizLogsByPlanId(String planId) {
    if (StringUtils.isEmpty(planId)) {
      return Collections.emptyList();
    }

    return replayBizLogRepository.queryByPlanId(planId);
  }

  public List<ReplayBizLogCollection> queryBizLogsByPlanId(String planId,
      ReplayBizLogQueryCondition condition) {
    if (StringUtils.isEmpty(planId)) {
      return Collections.emptyList();
    }

    return replayBizLogRepository.queryByPlanId(planId, condition);
  }

  public long countBizLogsByPlanId(String planId, ReplayBizLogQueryCondition condition) {
    if (StringUtils.isEmpty(planId)) {
      return 0L;
    }

    return replayBizLogRepository.countByPlanId(planId, condition);
  }

  public void upload(List<BizLog> logs) {
    replayBizLogRepository.saveAll(logs);
  }
}
