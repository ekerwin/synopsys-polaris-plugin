/**
 * synopsys-polaris
 *
 * Copyright (c) 2020 Synopsys, Inc.
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package com.synopsys.integration.jenkins.polaris.extensions.buildstep;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

import com.synopsys.integration.exception.IntegrationException;
import com.synopsys.integration.jenkins.extensions.ChangeBuildStatusTo;
import com.synopsys.integration.jenkins.extensions.JenkinsIntLogger;
import com.synopsys.integration.jenkins.polaris.workflow.PolarisWorkflowStepFactory;
import com.synopsys.integration.stepworkflow.StepWorkflow;
import com.synopsys.integration.stepworkflow.StepWorkflowResponse;

import hudson.model.AbstractBuild;
import hudson.model.Result;

public class PolarisBuildStepWorkflow {
    private final String polarisCLiName;
    private final String polarisArguments;
    private final WaitForIssues waitForIssues;
    private final PolarisWorkflowStepFactory polarisWorkflowStepFactory;
    private final AbstractBuild<?, ?> build;

    public PolarisBuildStepWorkflow(final String polarisCLiName, final String polarisArguments, final WaitForIssues waitForIssues, final PolarisWorkflowStepFactory polarisWorkflowStepFactory,
        final AbstractBuild<?, ?> build) {
        this.polarisCLiName = polarisCLiName;
        this.polarisArguments = polarisArguments;
        this.waitForIssues = waitForIssues;
        this.polarisWorkflowStepFactory = polarisWorkflowStepFactory;
        this.build = build;
    }

    public boolean perform() throws InterruptedException, IOException {
        final JenkinsIntLogger logger = polarisWorkflowStepFactory.getOrCreateLogger();
        final int jobTimeoutInMinutes = Optional.ofNullable(waitForIssues)
                                            .map(WaitForIssues::getJobTimeoutInMinutes)
                                            .orElse(0);

        return StepWorkflow
                   .first(polarisWorkflowStepFactory.createStepCreatePolarisEnvironment())
                   .then(polarisWorkflowStepFactory.createStepFindPolarisCli(polarisCLiName))
                   .then(polarisWorkflowStepFactory.createStepExecutePolarisCli(polarisArguments))
                   .andSometimes(polarisWorkflowStepFactory.createStepGetPolarisCliResponseContent())
                   .then(polarisWorkflowStepFactory.createStepGetTotalIssueCount(jobTimeoutInMinutes))
                   .then(polarisWorkflowStepFactory.createStepWithConsumer(issueCount -> setBuildStatusOnIssues(logger, issueCount, build)))
                   .butOnlyIf(waitForIssues, Objects::nonNull)
                   .run()
                   .handleResponse(response -> afterPerform(logger, response));
    }

    private boolean afterPerform(final JenkinsIntLogger logger, final StepWorkflowResponse<Object> stepWorkflowResponse) {
        final boolean wasSuccessful = stepWorkflowResponse.wasSuccessful();
        try {
            if (!wasSuccessful) {
                throw stepWorkflowResponse.getException();
            }
        } catch (final InterruptedException e) {
            logger.error("[ERROR] Synopsys Polaris thread was interrupted.", e);
            build.setResult(Result.ABORTED);
            Thread.currentThread().interrupt();
        } catch (final IntegrationException e) {
            this.handleException(logger, build, Result.FAILURE, e);
        } catch (final Exception e) {
            this.handleException(logger, build, Result.UNSTABLE, e);
        }

        return stepWorkflowResponse.wasSuccessful();
    }

    private void setBuildStatusOnIssues(final JenkinsIntLogger logger, final Integer issueCount, final AbstractBuild<?, ?> build) {
        final ChangeBuildStatusTo buildStatusToSet;
        if (waitForIssues == null || waitForIssues.getBuildStatusForIssues() == null) {
            buildStatusToSet = ChangeBuildStatusTo.SUCCESS;
        } else {
            buildStatusToSet = waitForIssues.getBuildStatusForIssues();
        }

        logger.alwaysLog("Polaris Issue Check");
        logger.alwaysLog("Build state for issues: " + buildStatusToSet.getDisplayName());
        logger.alwaysLog(String.format("Found %s issues", issueCount));

        if (issueCount > 0) {
            final Result result = buildStatusToSet.getResult();
            logger.alwaysLog("Setting build status to " + result.toString());
            build.setResult(result);
        }
    }

    private void handleException(final JenkinsIntLogger logger, final AbstractBuild<?, ?> build, final Result result, final Exception e) {
        logger.error("[ERROR] " + e.getMessage());
        logger.debug(e.getMessage(), e);
        build.setResult(result);
    }
}
