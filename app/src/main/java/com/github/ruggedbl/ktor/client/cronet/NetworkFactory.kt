package com.github.ruggedbl.ktor.client.cronet

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.plugins.logging.MessageLengthLimitingLogger
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.readBytes
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.chromium.net.ConnectionMigrationOptions
import org.chromium.net.CronetEngine
import org.chromium.net.DnsOptions
import java.io.File

class NetworkFactory {
    fun sendTest(context: Context) {
        GlobalScope.launch(Dispatchers.IO) {
            val client = NetworkFactory().createHttpClient(context)
            val a = client.get("https://quic.nginx.org/test")
            a.bodyAsBytes()

            delay(100)

            try {
                val b = client.get("https://quic.nginx.orgg/test")
                b.bodyAsBytes()
            } catch (e: Exception) {
                Log.e("Ktor", "Request failed", e)
            }

            delay(100)

            val c = client.get("https://www.google.com/favicon.ico")
            c.bodyAsBytes()

            val postResponse = client.post("https://jsonplaceholder.typicode.com/posts") {
                contentType(ContentType.Application.Json)
                setBody(PostRequest("foo", "bar", 1))
            }.body<PostResponse>()
            Log.d("Ktor", "Post response: $postResponse")
        }
    }

    suspend fun createHttpClient(context: Context) = withContext(Dispatchers.IO) {
        val cronet = createCronet(context)
        createKtor(cronet)
    }

    @OptIn(ConnectionMigrationOptions.Experimental::class, DnsOptions.Experimental::class)
    private fun createCronet(context: Context): CronetEngine {
        val storagePath = context.cacheDir.absolutePath + "/cronet"
        val storageFile = File(storagePath)
        if (!storageFile.exists()) {
            storageFile.mkdirs()
        }

        return CronetEngine.Builder(context)
            .enableHttp2(true)
            .enableQuic(true)
            .enableBrotli(true)
            .setConnectionMigrationOptions(
                ConnectionMigrationOptions.builder()
                    .enableDefaultNetworkMigration(true)
                    .build()
            )
            .setDnsOptions(
                DnsOptions.builder()
                    .persistHostCache(true)
                    .build()
            )
            .setStoragePath(storagePath)
            .setUserAgent("Android/1.0")
            .build()
    }

    private fun createKtor(cronetEngine: CronetEngine): HttpClient {
        return createKtor(Cronet(cronetEngine)) {
            engine {
                this.pipelining = true
                this.followRedirects = false
            }
        }
    }

    private fun <T : HttpClientEngineConfig> createKtor(
        httpClientEngine: HttpClientEngineFactory<T>,
        block: HttpClientConfig<T>.() -> Unit
    ): HttpClient {
        return HttpClient(httpClientEngine) {
            block.invoke(this)

            expectSuccess = true

            followRedirects = false

            install(ContentNegotiation) {
                json()
            }

            install(Logging) {
                logger = MessageLengthLimitingLogger(delegate = object : Logger {
                    override fun log(message: String) {
                        Log.v("Ktor", message)
                    }
                })
                level = LogLevel.ALL
            }
        }
    }

}