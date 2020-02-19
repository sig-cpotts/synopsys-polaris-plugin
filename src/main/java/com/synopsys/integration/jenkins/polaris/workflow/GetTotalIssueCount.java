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
package com.synopsys.integration.jenkins.polaris.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;

import com.synopsys.integration.exception.IntegrationException;
import com.synopsys.integration.jenkins.extensions.JenkinsIntLogger;
import com.synopsys.integration.polaris.common.api.job.model.Job;
import com.synopsys.integration.polaris.common.api.job.model.JobAttributes;
import com.synopsys.integration.polaris.common.api.job.model.JobResource;
import com.synopsys.integration.polaris.common.api.job.model.JobStatus;
import com.synopsys.integration.polaris.common.api.query.model.CountV0;
import com.synopsys.integration.polaris.common.api.query.model.CountV0Attributes;
import com.synopsys.integration.polaris.common.api.query.model.CountV0Resources;
import com.synopsys.integration.polaris.common.cli.PolarisCliResponseUtility;
import com.synopsys.integration.polaris.common.cli.model.BlackDuckScaToolInfo;
import com.synopsys.integration.polaris.common.cli.model.CoverityToolInfo;
import com.synopsys.integration.polaris.common.cli.model.IssueSummary;
import com.synopsys.integration.polaris.common.cli.model.PolarisCliResponseModel;
import com.synopsys.integration.polaris.common.cli.model.ScanInfo;
import com.synopsys.integration.polaris.common.exception.PolarisIntegrationException;
import com.synopsys.integration.polaris.common.request.PolarisRequestFactory;
import com.synopsys.integration.polaris.common.service.JobService;
import com.synopsys.integration.polaris.common.service.PolarisService;
import com.synopsys.integration.stepworkflow.SubStep;
import com.synopsys.integration.stepworkflow.SubStepResponse;

public class GetTotalIssueCount implements SubStep<String, Integer> {
    private final JenkinsIntLogger logger;
    private final PolarisService polarisService;
    private final JobService jobService;

    public GetTotalIssueCount(final JenkinsIntLogger logger, final PolarisService polarisService, final JobService jobService) {
        this.logger = logger;
        this.polarisService = polarisService;
        this.jobService = jobService;
    }

    @Override
    public SubStepResponse<Integer> run(final SubStepResponse<? extends String> previousResponse) {
        if (previousResponse.isFailure() || !previousResponse.hasData()) {
            return SubStepResponse.FAILURE(previousResponse);
        }

        final PolarisCliResponseUtility polarisCliResponseUtility = PolarisCliResponseUtility.defaultUtility(logger);
        final String rawJson = previousResponse.getData();
        final PolarisCliResponseModel polarisCliResponseModel = polarisCliResponseUtility.getPolarisCliResponseModelFromString(rawJson);
        final IssueSummary issueSummary = polarisCliResponseModel.getIssueSummary();
        final ScanInfo scanInfo = polarisCliResponseModel.getScanInfo();

        if (issueSummary != null) {
            logger.debug("Found total issue count, scan must have been run with -w");
            return SubStepResponse.SUCCESS(issueSummary.getTotalIssueCount());
        }

        try {
            final String issueApiUrl = Optional.ofNullable(scanInfo)
                                           .map(ScanInfo::getIssueApiUrl)
                                           .filter(StringUtils::isBlank)
                                           .orElseThrow(() -> new PolarisIntegrationException(
                                               "Synopsys Polaris for Jenkins cannot find the total issue count or issue api url in the cli-scan.json. Please ensure that you are using a supported version of the Polaris CLI."
                                           ));

            logger.debug("Found issue api url, polling for issues");
            final List<String> jobStatusUrls = new ArrayList<>();

            Optional.ofNullable(polarisCliResponseModel.getBlackDuckScaToolInfo())
                .map(BlackDuckScaToolInfo::getJobStatusUrl)
                .ifPresent(jobStatusUrls::add);

            Optional.ofNullable(polarisCliResponseModel.getCoverityToolInfo())
                .map(CoverityToolInfo::getJobStatusUrl)
                .ifPresent(jobStatusUrls::add);

            for (final String jobStatusUrl : jobStatusUrls) {
                // TODO: This is probably a common function-- we should move this into jobService
                final JobStatus jobStatus = jobService.getJobByUrl(jobStatusUrl)
                                                .map(JobResource::getData)
                                                .map(Job::getAttributes)
                                                .map(JobAttributes::getStatus)
                                                .orElseThrow(() -> new PolarisIntegrationException("Could not get job status from url " + jobStatusUrl + " has the cli-scan.json been modified?"));
                final JobStatus.StateEnum stateEnum = jobStatus.getState();
                if (JobStatus.StateEnum.QUEUED.equals(stateEnum) || JobStatus.StateEnum.RUNNING.equals(stateEnum) || JobStatus.StateEnum.DISPATCHED.equals(stateEnum)) {
                    logger.alwaysLog("Waiting for jobs to complete");
                    Thread.sleep(500);
                }
            }

            final CountV0Resources countV0Resources = polarisService.get(CountV0Resources.class, PolarisRequestFactory.createDefaultBuilder().uri(issueApiUrl).build());
            final List<CountV0> countV0s = countV0Resources.getData();
            return SubStepResponse.SUCCESS(countV0s.stream()
                                               .map(CountV0::getAttributes)
                                               .mapToInt(CountV0Attributes::getValue)
                                               .sum());

        } catch (final InterruptedException | IntegrationException e) {
            return SubStepResponse.FAILURE(e);
        }
    }

}