package com.github.ruggedbl.ktor.client.cronet

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory

class Cronet(private val preconfigured: org.chromium.net.CronetEngine) : HttpClientEngineFactory<CronetConfig> {
    override fun create(block: CronetConfig.() -> Unit): HttpClientEngine {
        return CronetEngine(CronetConfig(preconfigured).apply(block))
    }
}
