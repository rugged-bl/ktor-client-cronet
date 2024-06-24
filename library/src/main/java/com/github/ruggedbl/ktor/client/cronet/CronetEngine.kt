package com.github.ruggedbl.ktor.client.cronet

import android.util.Log
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.callContext
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.InternalAPI
import io.ktor.util.date.GMTDate
import io.ktor.util.flattenForEach
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.chromium.net.apihelpers.UploadDataProviders
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CronetEngine(
    override val config: CronetConfig,
) : HttpClientEngineBase("ktor-cronet") {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = hashSetOf(HttpTimeout)

    private val cronetEngine = config.preconfigured

    @OptIn(ExperimentalCoroutinesApi::class)
    override val dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(config.threadsCount)

    private val executor by lazy { dispatcher.asExecutor() }

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()

        return executeHttpRequest(callContext, data)
    }

    private suspend fun executeHttpRequest(
        callContext: CoroutineContext,
        data: HttpRequestData
    ): HttpResponseData = suspendCancellableCoroutine { continuation ->
        val requestTime = GMTDate()

        // All chunked response is written to this.
        val responseCache = ByteArrayOutputStream()
        val receiveChannel = Channels.newChannel(responseCache)

        val callback = object : UrlRequest.Callback() {
            override fun onRedirectReceived(
                request: UrlRequest,
                info: UrlResponseInfo,
                newLocationUrl: String
            ) {
                if (config.followRedirects) {
                    request.followRedirect()
                } else {
                    request.cancel()
                    continuation.resume(
                        info.toHttpResponseData(
                            requestTime = requestTime,
                            callContext = callContext,
                        )
                    )
                }
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                request.read(ByteBuffer.allocateDirect(config.responseBufferSize))
            }

            override fun onReadCompleted(
                request: UrlRequest,
                info: UrlResponseInfo,
                byteBuffer: ByteBuffer
            ) {
                // Write current received response data to responseCache
                byteBuffer.flip()
                receiveChannel.write(byteBuffer)

                // Continue reading
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                continuation.resume(
                    info.toHttpResponseData(
                        requestTime = requestTime,
                        callContext = callContext,
                        responseBody = responseCache.toByteArray(),
                    )
                )
            }

            override fun onFailed(
                request: UrlRequest,
                info: UrlResponseInfo,
                error: CronetException
            ) {
                continuation.resumeWithException(error)
            }
        }

        val request = cronetEngine.newUrlRequestBuilder(
            /* url = */ data.url.toString(),
            /* callback = */ callback,
            /* executor = */ executor,
        ).apply {
            setHttpMethod(data.method.value)

            data.headers.flattenForEach { key, value ->
                addHeader(key, value)
            }

            data.body.toUploadDataProvider()?.let {
                setUploadDataProvider(it, executor)
            }

            data.body.contentType?.let {
                addHeader(HttpHeaders.ContentType, "${it.contentType}/${it.contentSubtype}")
            }
        }.build()

        request.start()

        continuation.invokeOnCancellation {
            request.cancel()
        }
    }
}

private fun UrlResponseInfo.toHttpResponseData(
    requestTime: GMTDate,
    callContext: CoroutineContext,
    responseBody: ByteArray? = null,
): HttpResponseData {
    Log.v("CronetEngine", "protocol: $negotiatedProtocol")
    return HttpResponseData(
        statusCode = HttpStatusCode.fromValue(httpStatusCode),
        requestTime = requestTime,
        headers = Headers.build {
            allHeaders.forEach { (key, value) ->
                appendAll(key, value)
            }
        },
        version = when (negotiatedProtocol) {
            "h2" -> HttpProtocolVersion.HTTP_2_0
            "h3" -> HttpProtocolVersion.QUIC
            "quic/1+spdy/3" -> HttpProtocolVersion.SPDY_3
            else -> HttpProtocolVersion.HTTP_1_1
        },
        body = responseBody?.let { ByteReadChannel(it) } ?: ByteReadChannel.Empty,
        callContext = callContext,
    )
}

private fun OutgoingContent.toUploadDataProvider(): UploadDataProvider? {
    return when (this) {
        is OutgoingContent.NoContent -> null
        is OutgoingContent.ByteArrayContent -> {
            UploadDataProviders.create(bytes())
        }

        is OutgoingContent.ReadChannelContent,
        is OutgoingContent.WriteChannelContent,
        is OutgoingContent.ProtocolUpgrade -> error("UnsupportedContentType $this")
    }
}