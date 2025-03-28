/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.storage

import org.opensearch.commons.storage.api.StorageEngine
import org.opensearch.commons.storage.api.StorageOperation
import org.opensearch.commons.storage.api.StorageRepository
import org.opensearch.commons.storage.model.StorageRequest
import org.opensearch.commons.storage.model.StorageResponse
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest

class DynamoDbStorageRepository(private val client: DynamoDbClient) : StorageRepository {
    val ENGINE = StorageEngine.DYNAMO_DB

    override suspend fun save(request: StorageRequest<Any>): StorageResponse<Any> {
        return try {
            val putItemRequest = request.payload as? PutItemRequest
                ?: throw IllegalArgumentException("PutItemRequest expected")
            val putItemResponse = client.putItem(putItemRequest)
            StorageResponse(
                payload = putItemResponse,
                operation = StorageOperation.SAVE,
                engine = ENGINE
            )
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun get(request: StorageRequest<Any>): StorageResponse<Any> {
        TODO("Not yet implemented")
    }

    override suspend fun search(request: StorageRequest<Any>): StorageResponse<Any> {
        TODO("Not yet implemented")
    }

    override suspend fun update(request: StorageRequest<Any>): StorageResponse<Any> {
        TODO("Not yet implemented")
    }

    override suspend fun delete(request: StorageRequest<Any>): StorageResponse<Any> {
        TODO("Not yet implemented")
    }
}