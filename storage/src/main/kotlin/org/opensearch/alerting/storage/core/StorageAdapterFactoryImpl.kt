package org.opensearch.alerting.storage.core

import org.opensearch.commons.storage.api.StorageAdapterFactory
import org.opensearch.commons.storage.api.StorageEngine
import org.opensearch.commons.storage.api.StorageRepository

class StorageAdapterFactoryImpl(private val adapters: Map<StorageEngine, StorageRepository>) : StorageAdapterFactory {
    override fun getAdapter(engine: StorageEngine): StorageRepository {
        return adapters[engine] ?: error("No adapter found for engine: $engine")
    }
}