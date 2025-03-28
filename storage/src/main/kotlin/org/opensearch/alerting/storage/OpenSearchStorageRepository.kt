/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.storage

import org.apache.logging.log4j.LogManager
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.index.IndexResponse
import org.opensearch.action.search.SearchRequest
import org.opensearch.action.search.SearchResponse
import org.opensearch.alerting.opensearchapi.suspendUntil
import org.opensearch.commons.storage.api.StorageEngine
import org.opensearch.commons.storage.api.StorageOperation
import org.opensearch.commons.storage.api.StorageRepository
import org.opensearch.commons.storage.model.StorageRequest
import org.opensearch.commons.storage.model.StorageResponse
import org.opensearch.transport.client.Client


class OpenSearchStorageRepository(private val client: Client) : StorageRepository {
    private val STORAGE_ENGINE = StorageEngine.OPEN_SEARCH_CLUSTER
    private val log = LogManager.getLogger(OpenSearchStorageRepository::class.java)

    override suspend fun save(request: StorageRequest<Any>): StorageResponse<Any> {
        return try {
            val indexRequest: IndexRequest = requireNotNull(request.payload) as IndexRequest
            val indexResponse: IndexResponse = client.suspendUntil { client.index(indexRequest, it) }

            StorageResponse(
                payload = indexResponse,
                operation = StorageOperation.SAVE,
                engine = STORAGE_ENGINE
            )
        } catch (e: Exception) {
            log.error("Failed to index monitor with exception: ${e.message}")
            throw e
        }
    }

    override suspend fun get(request: StorageRequest<Any>): StorageResponse<Any> {
        return try {
            val getRequest: GetRequest = requireNotNull(request.payload) as GetRequest
            val getResponse: GetResponse = client.suspendUntil { client.get(getRequest, it) }

            StorageResponse(
                payload = getResponse,
                operation = StorageOperation.GET,
                engine = STORAGE_ENGINE
            )
        } catch (e: Exception) {
            log.error("Failed to get monitor with exception: ${e.message}")
            throw e
        }
    }

    override suspend fun search(request: StorageRequest<Any>): StorageResponse<Any> {
        return try {
            val searchRequest: SearchRequest = requireNotNull(request.payload) as SearchRequest
            val searchResponse: SearchResponse = client.suspendUntil { client.search(searchRequest, it) }

            StorageResponse(
                payload = searchResponse,
                operation = StorageOperation.SEARCH,
                engine = STORAGE_ENGINE
            )
        } catch (e: Exception) {
            log.error("Failed to search monitor with exception: ${e.message}")
            throw e
        }
    }

    override suspend fun update(request: StorageRequest<Any>): StorageResponse<Any> {
        TODO("Not yet implemented")
    }

    override suspend fun delete(request: StorageRequest<Any>): StorageResponse<Any> {
        TODO("Not yet implemented")
    }
}