/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.util.destinationmigration

import org.apache.logging.log4j.LogManager
import org.opensearch.action.bulk.BulkRequest
import org.opensearch.action.bulk.BulkResponse
import org.opensearch.action.delete.DeleteRequest
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.action.support.WriteRequest
import org.opensearch.alerting.model.destination.Destination
import org.opensearch.alerting.model.destination.email.EmailAccount
import org.opensearch.alerting.model.destination.email.EmailGroup
import org.opensearch.alerting.opensearchapi.suspendUntil
import org.opensearch.alerting.util.destinationmigration.DestinationConversionUtils.Companion.convertDestinationToNotificationConfig
import org.opensearch.alerting.util.destinationmigration.DestinationConversionUtils.Companion.convertEmailAccountToNotificationConfig
import org.opensearch.alerting.util.destinationmigration.DestinationConversionUtils.Companion.convertEmailGroupToNotificationConfig
import org.opensearch.alerting.util.destinationmigration.NotificationApiUtils.Companion.createNotificationConfig
import org.opensearch.alerting.util.use
import org.opensearch.common.xcontent.LoggingDeprecationHandler
import org.opensearch.common.xcontent.XContentType
import org.opensearch.commons.ConfigConstants
import org.opensearch.commons.alerting.model.ScheduledJob
import org.opensearch.commons.notifications.action.CreateNotificationConfigRequest
import org.opensearch.commons.notifications.model.NotificationConfig
import org.opensearch.commons.notifications.model.NotificationConfigInfo
import org.opensearch.core.common.Strings
import org.opensearch.core.rest.RestStatus
import org.opensearch.core.xcontent.NamedXContentRegistry
import org.opensearch.core.xcontent.XContentParser
import org.opensearch.core.xcontent.XContentParserUtils
import org.opensearch.index.query.QueryBuilders
import org.opensearch.search.builder.SearchSourceBuilder
import org.opensearch.search.fetch.subphase.FetchSourceContext
import org.opensearch.transport.client.node.NodeClient
import java.time.Instant

class DestinationMigrationUtilService {

    companion object {

        private val logger = LogManager.getLogger(DestinationMigrationUtilService::class)

        @Volatile
        private var runningLock = false // In case 2 migrateDestinations() processes are running

        // Used in DestinationMigrationCoordinator to cancel scheduled process
        @Volatile
        var finishFlag = false
            internal set

        suspend fun migrateDestinations(client: NodeClient) {
            if (runningLock) {
                logger.info("There is already a migrate destination process running...")
                return
            } else if (finishFlag) {
                logger.info("Destination migration has finished.")
                return
            }
            try {
                runningLock = true

                val emailAccountsToMigrate = retrieveConfigsToMigrate(client, "email_account")
                val emailGroupsToMigrate = retrieveConfigsToMigrate(client, "email_group")
                val destinationsToMigrate = retrieveConfigsToMigrate(client, "destination")
                val configsToMigrate = emailAccountsToMigrate + emailGroupsToMigrate + destinationsToMigrate
                logger.info(
                    "Need to migrate ${emailAccountsToMigrate.size} email accounts, " +
                        "${emailGroupsToMigrate.size} email groups and " +
                        "${destinationsToMigrate.size} destinations " +
                        "(${configsToMigrate.size} configs total)"
                )
                if (configsToMigrate.isEmpty()) {
                    finishFlag = true
                    runningLock = false
                    return
                }
                val migratedConfigs = createNotificationChannelIfNotExists(client, configsToMigrate)
                logger.info("Migrated ${migratedConfigs.size} configs")
                val failedDeleteConfigs = deleteOldDestinations(client, migratedConfigs)
                logger.info("Failed to delete ${failedDeleteConfigs.size} configs from migration process cleanup")
            } finally {
                runningLock = false
            }
        }

        private suspend fun deleteOldDestinations(client: NodeClient, destinationIds: List<String>): List<String> {
            val bulkDeleteRequest = BulkRequest().setRefreshPolicy(WriteRequest.RefreshPolicy.IMMEDIATE)
            destinationIds.forEach {
                val deleteRequest = DeleteRequest(ScheduledJob.SCHEDULED_JOBS_INDEX, it)
                bulkDeleteRequest.add(deleteRequest)
            }

            val failedToDeleteDestinations = mutableListOf<String>()
            try {
                val bulkResponse: BulkResponse = client.suspendUntil { client.bulk(bulkDeleteRequest, it) }
                failedToDeleteDestinations.addAll(bulkResponse.items.filter { it.isFailed }.map { it.id })
            } catch (e: Exception) {
                logger.error("Failed to delete all destinations", e)
                failedToDeleteDestinations.addAll(destinationIds)
            }
            return failedToDeleteDestinations
        }

        private suspend fun createNotificationChannelIfNotExists(
            client: NodeClient,
            notificationConfigInfoList: List<Pair<NotificationConfigInfo, String>>
        ): List<String> {
            val migratedNotificationConfigs = mutableListOf<String>()
            notificationConfigInfoList.forEach {
                val notificationConfigInfo = it.first
                val userStr = it.second
                val createNotificationConfigRequest = CreateNotificationConfigRequest(
                    notificationConfigInfo.notificationConfig,
                    notificationConfigInfo.configId
                )
                try {
                    // TODO: recreate user object to pass along the same permissions. Make sure this works when user based security is removed
                    client.threadPool().threadContext.stashContext().use {
                        if (userStr.isNotBlank()) {
                            client.threadPool().threadContext
                                .putTransient(ConfigConstants.OPENSEARCH_SECURITY_USER_INFO_THREAD_CONTEXT, userStr)
                        }
                        val createResponse = createNotificationConfig(client, createNotificationConfigRequest)
                        migratedNotificationConfigs.add(createResponse.configId)
                        logger.debug(("Migrated destination: ${createResponse.configId}"))
                    }
                } catch (e: Exception) {
                    if (e.message?.contains("version conflict, document already exists") == true) {
                        migratedNotificationConfigs.add(notificationConfigInfo.configId)
                    } else {
                        logger.warn(
                            "Failed to migrate over Destination ${notificationConfigInfo.configId} because failed to " +
                                "create channel in Notification plugin.",
                            e
                        )
                    }
                }
            }
            return migratedNotificationConfigs
        }

        private suspend fun retrieveConfigsToMigrate(client: NodeClient, configName: String): List<Pair<NotificationConfigInfo, String>> {
            var start = 0
            val size = 100
            val notificationConfigInfoList = mutableListOf<Pair<NotificationConfigInfo, String>>()
            var hasMoreResults = true

            while (hasMoreResults) {
                val searchSourceBuilder = SearchSourceBuilder()
                    .size(size)
                    .from(start)
                    .fetchSource(FetchSourceContext(true, Strings.EMPTY_ARRAY, Strings.EMPTY_ARRAY))
                    .seqNoAndPrimaryTerm(true)
                    .version(true)
                val queryBuilder = QueryBuilders.boolQuery()
                    .should(QueryBuilders.existsQuery(configName))
                searchSourceBuilder.query(queryBuilder)

                val searchRequest = SearchRequest()
                    .source(searchSourceBuilder)
                    .indices(ScheduledJob.SCHEDULED_JOBS_INDEX)
                val response: SearchResponse = client.suspendUntil { client.search(searchRequest, it) }

                if (response.status() != RestStatus.OK) {
                    logger.error("Failed to retrieve ${configName}s to migrate")
                    hasMoreResults = false
                } else {
                    if (response.hits.hits.isEmpty()) {
                        hasMoreResults = false
                    }
                    for (hit in response.hits) {
                        val xcp = XContentType.JSON.xContent()
                            .createParser(NamedXContentRegistry.EMPTY, LoggingDeprecationHandler.INSTANCE, hit.sourceAsString)
                        var notificationConfig: NotificationConfig? = null
                        var userStr = ""
                        when (configName) {
                            "email_group" -> {
                                val emailGroup = EmailGroup.parseWithType(xcp, hit.id, hit.version)
                                notificationConfig = convertEmailGroupToNotificationConfig(emailGroup)
                            }
                            "email_account" -> {
                                val emailAccount = EmailAccount.parseWithType(xcp, hit.id, hit.version)
                                notificationConfig = convertEmailAccountToNotificationConfig(emailAccount)
                            }
                            "destination" -> {
                                XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, xcp.nextToken(), xcp)
                                XContentParserUtils.ensureExpectedToken(XContentParser.Token.FIELD_NAME, xcp.nextToken(), xcp)
                                XContentParserUtils.ensureExpectedToken(XContentParser.Token.START_OBJECT, xcp.nextToken(), xcp)
                                val destination = Destination.parse(
                                    xcp,
                                    hit.id,
                                    hit.version,
                                    hit.seqNo.toInt(),
                                    hit.primaryTerm.toInt()
                                )
                                userStr = destination.user.toString()
                                notificationConfig = convertDestinationToNotificationConfig(destination)
                            }
                            else -> logger.info("Unrecognized config name [$configName] to migrate")
                        }

                        if (notificationConfig != null)
                            notificationConfigInfoList.add(
                                Pair(
                                    NotificationConfigInfo(
                                        hit.id,
                                        Instant.now(),
                                        Instant.now(),
                                        notificationConfig
                                    ),
                                    userStr
                                )
                            )
                    }
                }

                start += size
            }

            return notificationConfigInfoList
        }
    }
}
