/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.task;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.data.pipeline.api.executor.AbstractLifecycleExecutor;
import org.apache.shardingsphere.data.pipeline.api.executor.LifecycleExecutor;
import org.apache.shardingsphere.data.pipeline.common.config.job.PipelineJobConfiguration;
import org.apache.shardingsphere.data.pipeline.common.execute.ExecuteCallback;
import org.apache.shardingsphere.data.pipeline.common.execute.ExecuteEngine;
import org.apache.shardingsphere.data.pipeline.common.job.JobStatus;
import org.apache.shardingsphere.data.pipeline.common.job.type.JobType;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.PipelineDataConsistencyChecker;
import org.apache.shardingsphere.data.pipeline.core.consistencycheck.result.TableDataConsistencyCheckResult;
import org.apache.shardingsphere.data.pipeline.core.job.PipelineJobIdUtils;
import org.apache.shardingsphere.data.pipeline.core.job.service.InventoryIncrementalJobAPI;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineAPIFactory;
import org.apache.shardingsphere.data.pipeline.core.job.service.PipelineJobAPI;
import org.apache.shardingsphere.data.pipeline.core.task.runner.PipelineTasksRunner;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.api.impl.ConsistencyCheckJobAPI;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.config.ConsistencyCheckJobConfiguration;
import org.apache.shardingsphere.data.pipeline.scenario.consistencycheck.context.ConsistencyCheckJobItemContext;
import org.apache.shardingsphere.infra.spi.type.typed.TypedSPILoader;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Consistency check tasks runner.
 */
@Slf4j
public final class ConsistencyCheckTasksRunner implements PipelineTasksRunner {
    
    private final ConsistencyCheckJobAPI checkJobAPI = new ConsistencyCheckJobAPI();
    
    @Getter
    private final ConsistencyCheckJobItemContext jobItemContext;
    
    private final ConsistencyCheckJobConfiguration checkJobConfig;
    
    private final String checkJobId;
    
    private final String parentJobId;
    
    private final LifecycleExecutor checkExecutor;
    
    private final AtomicReference<PipelineDataConsistencyChecker> consistencyChecker = new AtomicReference<>();
    
    public ConsistencyCheckTasksRunner(final ConsistencyCheckJobItemContext jobItemContext) {
        this.jobItemContext = jobItemContext;
        checkJobConfig = jobItemContext.getJobConfig();
        checkJobId = checkJobConfig.getJobId();
        parentJobId = checkJobConfig.getParentJobId();
        checkExecutor = new CheckLifecycleExecutor();
    }
    
    @Override
    public void start() {
        if (jobItemContext.isStopping()) {
            return;
        }
        TypedSPILoader.getService(PipelineJobAPI.class, PipelineJobIdUtils.parseJobType(jobItemContext.getJobId()).getType()).persistJobItemProgress(jobItemContext);
        CompletableFuture<?> future = jobItemContext.getProcessContext().getConsistencyCheckExecuteEngine().submit(checkExecutor);
        ExecuteEngine.trigger(Collections.singletonList(future), new CheckExecuteCallback());
    }
    
    @Override
    public void stop() {
        jobItemContext.setStopping(true);
        checkExecutor.stop();
    }
    
    private final class CheckLifecycleExecutor extends AbstractLifecycleExecutor {
        
        @Override
        protected void runBlocking() {
            checkJobAPI.persistJobItemProgress(jobItemContext);
            JobType jobType = PipelineJobIdUtils.parseJobType(parentJobId);
            InventoryIncrementalJobAPI jobAPI = (InventoryIncrementalJobAPI) TypedSPILoader.getService(PipelineJobAPI.class, jobType.getType());
            PipelineJobConfiguration parentJobConfig = jobAPI.getJobConfiguration(parentJobId);
            try {
                PipelineDataConsistencyChecker checker = jobAPI.buildPipelineDataConsistencyChecker(
                        parentJobConfig, jobAPI.buildPipelineProcessContext(parentJobConfig), jobItemContext.getProgressContext());
                consistencyChecker.set(checker);
                Map<String, TableDataConsistencyCheckResult> checkResultMap = checker.check(checkJobConfig.getAlgorithmTypeName(), checkJobConfig.getAlgorithmProps());
                log.info("job {} with check algorithm '{}' data consistency checker result: {}", parentJobId, checkJobConfig.getAlgorithmTypeName(), checkResultMap);
                PipelineAPIFactory.getGovernanceRepositoryAPI(PipelineJobIdUtils.parseContextKey(parentJobId)).persistCheckJobResult(parentJobId, checkJobId, checkResultMap);
            } finally {
                jobItemContext.getProgressContext().setCheckEndTimeMillis(System.currentTimeMillis());
            }
        }
        
        @Override
        protected void doStop() {
            PipelineDataConsistencyChecker checker = consistencyChecker.get();
            if (null != checker) {
                checker.cancel();
            }
        }
    }
    
    private final class CheckExecuteCallback implements ExecuteCallback {
        
        @Override
        public void onSuccess() {
            log.info("onSuccess, check job id: {}, parent job id: {}", checkJobId, parentJobId);
            jobItemContext.setStatus(JobStatus.FINISHED);
            checkJobAPI.persistJobItemProgress(jobItemContext);
            checkJobAPI.stop(checkJobId);
        }
        
        @Override
        public void onFailure(final Throwable throwable) {
            PipelineDataConsistencyChecker checker = consistencyChecker.get();
            if (null != checker && checker.isCanceling()) {
                log.info("onFailure, canceling, check job id: {}, parent job id: {}", checkJobId, parentJobId);
                checkJobAPI.stop(checkJobId);
                return;
            }
            log.info("onFailure, check job id: {}, parent job id: {}", checkJobId, parentJobId, throwable);
            checkJobAPI.persistJobItemErrorMessage(checkJobId, 0, throwable);
            checkJobAPI.stop(checkJobId);
        }
    }
}
