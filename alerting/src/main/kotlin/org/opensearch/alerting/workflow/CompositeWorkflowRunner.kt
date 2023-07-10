/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.workflow

import org.apache.logging.log4j.LogManager
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.alerting.BucketLevelMonitorRunner
import org.opensearch.alerting.DocumentLevelMonitorRunner
import org.opensearch.alerting.MonitorRunnerExecutionContext
import org.opensearch.alerting.QueryLevelMonitorRunner
import org.opensearch.alerting.WorkflowMetadataService
import org.opensearch.alerting.alerts.AlertIndices
import org.opensearch.alerting.model.ChainedAlertTriggerRunResult
import org.opensearch.alerting.model.MonitorRunResult
import org.opensearch.alerting.model.WorkflowRunResult
import org.opensearch.alerting.opensearchapi.suspendUntil
import org.opensearch.alerting.script.ChainedAlertTriggerExecutionContext
import org.opensearch.alerting.util.AlertingException
import org.opensearch.alerting.util.isDocLevelMonitor
import org.opensearch.alerting.util.isQueryLevelMonitor
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.XContentHelper
import org.opensearch.common.xcontent.XContentParserUtils
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.alerting.model.Alert
import org.opensearch.commons.alerting.model.ChainedAlertTrigger
import org.opensearch.commons.alerting.model.CompositeInput
import org.opensearch.commons.alerting.model.DataSources
import org.opensearch.commons.alerting.model.Delegate
import org.opensearch.commons.alerting.model.Monitor
import org.opensearch.commons.alerting.model.Workflow
import org.opensearch.commons.alerting.util.isBucketLevelMonitor
import org.opensearch.core.xcontent.XContentParser
import org.opensearch.index.query.QueryBuilders
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

object CompositeWorkflowRunner : WorkflowRunner() {

    private val logger = LogManager.getLogger(javaClass)

    override suspend fun runWorkflow(
        workflow: Workflow,
        monitorCtx: MonitorRunnerExecutionContext,
        periodStart: Instant,
        periodEnd: Instant,
        dryRun: Boolean,
    ): WorkflowRunResult {
        val workflowExecutionStartTime = Instant.now()

        val isTempWorkflow = dryRun || workflow.id == Workflow.NO_ID

        val executionId = generateExecutionId(isTempWorkflow, workflow)

        val (workflowMetadata, _) = WorkflowMetadataService.getOrCreateWorkflowMetadata(
            workflow = workflow,
            skipIndex = isTempWorkflow,
            executionId = executionId
        )
        var dataSources: DataSources? = null
        logger.debug("Workflow ${workflow.id} in $executionId execution is running")
        val delegates = (workflow.inputs[0] as CompositeInput).sequence.delegates.sortedBy { it.order }
        var monitors: List<Monitor>

        try {
            monitors = monitorCtx.workflowService!!.getMonitorsById(delegates.map { it.monitorId }, delegates.size)
        } catch (e: Exception) {
            logger.error("Failed getting workflow delegates. Error: ${e.message}", e)
            return WorkflowRunResult(
                workflow.id,
                workflow.name,
                emptyList(),
                workflowExecutionStartTime,
                Instant.now(),
                executionId,
                AlertingException.wrap(e)
            )
        }
        // Validate the monitors size
        validateMonitorSize(delegates, monitors, workflow)
        val monitorsById = monitors.associateBy { it.id }
        val resultList = mutableListOf<MonitorRunResult<*>>()
        var lastErrorDelegateRun: Exception? = null

        for (delegate in delegates) {
            var indexToDocIds = mapOf<String, List<String>>()
            var delegateMonitor: Monitor
            delegateMonitor = monitorsById[delegate.monitorId]
                ?: throw AlertingException.wrap(
                    IllegalStateException("Delegate monitor not found ${delegate.monitorId} for the workflow $workflow.id")
                )
            if (delegate.chainedMonitorFindings != null) {
                val chainedMonitor = monitorsById[delegate.chainedMonitorFindings!!.monitorId]
                    ?: throw AlertingException.wrap(
                        IllegalStateException("Chained finding monitor not found ${delegate.monitorId} for the workflow $workflow.id")
                    )
                try {
                    indexToDocIds = monitorCtx.workflowService!!.getFindingDocIdsByExecutionId(chainedMonitor, executionId)
                } catch (e: Exception) {
                    logger.error("Failed to execute workflow due to failure in chained findings.  Error: ${e.message}", e)
                    return WorkflowRunResult(
                        workflow.id, workflow.name, emptyList(), workflowExecutionStartTime, Instant.now(), executionId,
                        AlertingException.wrap(e)
                    )
                }
            }
            val workflowRunContext = WorkflowRunContext(
                workflowId = workflowMetadata.workflowId,
                workflowMetadataId = workflowMetadata.id,
                chainedMonitorId = delegate.chainedMonitorFindings?.monitorId,
                executionId = executionId,
                matchingDocIdsPerIndex = indexToDocIds,
                auditDelegateMonitorAlerts = if (workflow.auditDelegateMonitorAlerts == null) true
                else workflow.auditDelegateMonitorAlerts!!
            )
            try {
                dataSources = delegateMonitor.dataSources
                val delegateRunResult =
                    runDelegateMonitor(delegateMonitor, monitorCtx, periodStart, periodEnd, dryRun, workflowRunContext)
                resultList.add(delegateRunResult!!)
            } catch (ex: Exception) {
                logger.error("Error executing workflow delegate monitor ${delegate.monitorId}", ex)
                lastErrorDelegateRun = AlertingException.wrap(ex)
                break
            }
        }
        logger.debug("Workflow ${workflow.id} delegate monitors in execution $executionId completed")
        // Update metadata only if the workflow is not temp
        if (!isTempWorkflow) {
            WorkflowMetadataService.upsertWorkflowMetadata(
                workflowMetadata.copy(latestRunTime = workflowExecutionStartTime, latestExecutionId = executionId),
                true
            )
        }
        val triggerResults = mutableMapOf<String, ChainedAlertTriggerRunResult>()
        val workflowRunResult = WorkflowRunResult(
            workflowId = workflow.id,
            workflowName = workflow.name,
            monitorRunResults = resultList,
            executionStartTime = workflowExecutionStartTime,
            executionEndTime = null,
            executionId = executionId,
            error = lastErrorDelegateRun,
            triggerResults = triggerResults
        )
        if (dataSources != null) {
            try {
                monitorCtx.alertIndices!!.createOrUpdateAlertIndex(dataSources)
                val monitorIdToAlertIdsMap = fetchAlertsGeneratedInCurrentExecution(dataSources, executionId, monitorCtx, workflow)
                for (trigger in workflow.triggers) {
                    val caTrigger = trigger as ChainedAlertTrigger
                    val triggerCtx = ChainedAlertTriggerExecutionContext(
                        workflow = workflow,
                        workflowRunResult = workflowRunResult,
                        trigger = caTrigger,
                        alertGeneratingMonitors = monitorIdToAlertIdsMap.keys,
                        monitorIdToAlertIdsMap = monitorIdToAlertIdsMap
                    )
                    runChainedAlertTrigger(dataSources, monitorCtx, workflow, trigger, executionId, triggerCtx, dryRun, triggerResults)
                }
            } catch (e: Exception) {
                // We can't save ERROR alerts to the index here as we don't know if there are existing ACTIVE alerts
                val id = if (workflow.id.trim().isEmpty()) "_na_" else workflow.id
                logger.error("Error loading current chained alerts for workflow: $id", e)
                return WorkflowRunResult(
                    workflowId = workflow.id,
                    workflowName = workflow.name,
                    monitorRunResults = emptyList(),
                    executionStartTime = workflowExecutionStartTime,
                    executionEndTime = Instant.now(),
                    executionId = executionId,
                    error = AlertingException.wrap(e),
                    triggerResults = emptyMap()
                )
            }
        }
        workflowRunResult.executionEndTime = Instant.now()

        val sr = SearchRequest(dataSources!!.alertsIndex)
        sr.source().query(QueryBuilders.matchAllQuery()).size(10)
        val searchResponse: SearchResponse = monitorCtx.client!!.suspendUntil { monitorCtx.client!!.search(sr, it) }
        searchResponse.hits
        return workflowRunResult
    }

    private suspend fun runDelegateMonitor(
        delegateMonitor: Monitor,
        monitorCtx: MonitorRunnerExecutionContext,
        periodStart: Instant,
        periodEnd: Instant,
        dryRun: Boolean,
        workflowRunContext: WorkflowRunContext,
    ): MonitorRunResult<*>? {

        if (delegateMonitor.isBucketLevelMonitor()) {
            return BucketLevelMonitorRunner.runMonitor(
                delegateMonitor,
                monitorCtx,
                periodStart,
                periodEnd,
                dryRun,
                workflowRunContext
            )
        } else if (delegateMonitor.isDocLevelMonitor()) {
            return DocumentLevelMonitorRunner.runMonitor(
                delegateMonitor,
                monitorCtx,
                periodStart,
                periodEnd,
                dryRun,
                workflowRunContext
            )
        } else if (delegateMonitor.isQueryLevelMonitor()) {
            return QueryLevelMonitorRunner.runMonitor(
                delegateMonitor,
                monitorCtx,
                periodStart,
                periodEnd,
                dryRun,
                workflowRunContext
            )
        } else {
            throw AlertingException.wrap(
                IllegalStateException("Unsupported monitor type ${delegateMonitor.monitorType}")
            )
        }
    }

    private fun generateExecutionId(
        isTempWorkflow: Boolean,
        workflow: Workflow,
    ): String {
        val randomPart = "_${LocalDateTime.now(ZoneOffset.UTC)}_${UUID.randomUUID()}"
        return if (isTempWorkflow) randomPart else workflow.id.plus(randomPart)
    }

    private fun validateMonitorSize(
        delegates: List<Delegate>,
        monitors: List<Monitor>,
        workflow: Workflow,
    ) {
        if (delegates.size != monitors.size) {
            val diffMonitorIds = delegates.map { it.monitorId }.minus(monitors.map { it.id }.toSet()).joinToString()
            logger.error("Delegate monitors don't exist $diffMonitorIds for the workflow $workflow.id")
            throw AlertingException.wrap(
                IllegalStateException("Delegate monitors don't exist $diffMonitorIds for the workflow $workflow.id")
            )
        }
    }

    private suspend fun runChainedAlertTrigger(
        dataSources: DataSources,
        monitorCtx: MonitorRunnerExecutionContext,
        workflow: Workflow,
        trigger: ChainedAlertTrigger,
        executionId: String,
        triggerCtx: ChainedAlertTriggerExecutionContext,
        dryRun: Boolean,
        triggerResults: MutableMap<String, ChainedAlertTriggerRunResult>,
    ) {
        val triggerRunResult = monitorCtx.triggerService!!.runChainedAlertTrigger(
            workflow, trigger, triggerCtx.alertGeneratingMonitors, triggerCtx.monitorIdToAlertIdsMap
        )
        triggerResults[trigger.id] = triggerRunResult
        if (triggerRunResult.triggered) {
            val actionCtx = triggerCtx
            for (action in trigger.actions) {
                triggerRunResult.actionResults[action.id] = this.runAction(action, actionCtx, monitorCtx, workflow, dryRun)
            }
            val alert = monitorCtx.alertService!!.composeChainedAlert(
                triggerCtx, executionId, workflow, triggerRunResult.associatedAlertIds.toList()
            )
            if (!dryRun && workflow.id != Workflow.NO_ID) {
                monitorCtx.retryPolicy?.let {
                    monitorCtx.alertService!!.saveAlerts(
                        dataSources,
                        listOf(alert),
                        it,
                        routingId = workflow.id
                    )
                }
            }
        }
    }

    private suspend fun fetchAlertsGeneratedInCurrentExecution(
        dataSources: DataSources,
        executionId: String,
        monitorCtx: MonitorRunnerExecutionContext,
        workflow: Workflow,
    ): MutableMap<String, MutableSet<String>> {
        try {
            val searchRequest =
                SearchRequest(getDelegateMonitorAlertIndex(dataSources, workflow, monitorCtx.alertIndices!!.isAlertHistoryEnabled()))
            val queryBuilder = QueryBuilders.boolQuery()
            queryBuilder.must(QueryBuilders.termQuery("execution_id", executionId))
            queryBuilder.must(QueryBuilders.termQuery("state", getDelegateMonitorAlertState(workflow)))
            searchRequest.source().query(queryBuilder)
            val searchResponse: SearchResponse = monitorCtx.client!!.suspendUntil { monitorCtx.client!!.search(searchRequest, it) }
            val alerts = searchResponse.hits.map { hit ->
                val xcp = XContentHelper.createParser(
                    monitorCtx.xContentRegistry, LoggingDeprecationHandler.INSTANCE,
                    hit.sourceRef, XContentType.JSON
                )
                XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, xcp.nextToken(), xcp)
                val alert = Alert.parse(xcp, hit.id, hit.version)
                alert
            }
            val map = mutableMapOf<String, MutableSet<String>>()
            for (alert in alerts) {
                if (map.containsKey(alert.monitorId)) {
                    map[alert.monitorId]!!.add(alert.id)
                } else {
                    map[alert.monitorId] = mutableSetOf(alert.id)
                }
            }
            return map
        } catch (e: Exception) {
            logger.error("failed to get alerts generated by delegate monitors in current execution $executionId", e)
            return mutableMapOf()
        }
    }

    // todo use flag in workflow
    fun getDelegateMonitorAlertIndex(
        dataSources: DataSources,
        workflow: Workflow,
        isAlertHistoryEnabled: Boolean,
    ): String {
        return if (workflow.triggers.isNotEmpty()) {
            if (isAlertHistoryEnabled) {
                dataSources.alertsHistoryIndex!!
            } else dataSources.alertsIndex
        } else dataSources.alertsIndex
    }

    fun getDelegateMonitorAlertState(
        workflow: Workflow,
    ): Alert.State {
        return if (workflow.triggers.isNotEmpty()) {
            Alert.State.AUDIT
        } else Alert.State.ACTIVE
    }
}
