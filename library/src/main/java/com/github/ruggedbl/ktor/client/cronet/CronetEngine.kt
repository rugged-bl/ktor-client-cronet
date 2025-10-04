package com.github.ruggedbl.ktor.client.cronet

import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineCapability
import io.ktor.client.engine.callContext
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.utils.dropCompressionHeaders
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.util.Attributes
import io.ktor.util.date.GMTDate
import io.ktor.util.flattenForEach
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import io.ktor.utils.io.toByteArray
import io.ktor.utils.io.writer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.coroutineScope
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

class CronetEngine internal constructor(
    override val config: CronetConfig,
) : HttpClientEngineBase("ktor-cronet") {

    override val supportedCapabilities: Set<HttpClientEngineCapability<*>> = emptySet()

    private val cronetEngine = config.preconfigured

    private val executor by lazy { dispatcher.asExecutor() }

    @InternalAPI
    override suspend fun execute(data: HttpRequestData): HttpResponseData {
        val callContext = callContext()

        return executeHttpRequest(callContext, data)
    }

    private suspend fun executeHttpRequest(
        callContext: CoroutineContext,
        data: HttpRequestData
    ): HttpResponseData {
        val requestTime = GMTDate()

        // All chunked response is written to this.
        val responseCache = ByteArrayOutputStream()
        val receiveChannel = Channels.newChannel(responseCache)

        val uploadDataProvider = data.body.toUploadDataProvider()

        return suspendCancellableCoroutine { continuation ->
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
                                method = data.method,
                                attributes = data.attributes,
                                requestTime = requestTime,
                                callContext = callContext,
                                responseBody = ByteReadChannel.Empty,
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
                            method = data.method,
                            attributes = data.attributes,
                            requestTime = requestTime,
                            callContext = callContext,
                            responseBody = ByteReadChannel(responseCache.toByteArray()),
                        )
                    )
                }

                override fun onFailed(
                    request: UrlRequest,
                    info: UrlResponseInfo?,
                    error: CronetException
                ) {
                    continuation.resumeWithException(error)
                }

                override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                    continuation.resumeWithException(CancellationException("Request was cancelled"))
                }
            }

            val request = buildUrlRequest(data, callback, uploadDataProvider)

            continuation.invokeOnCancellation {
                request.cancel()
            }

            request.start()
        }
    }

    private fun buildUrlRequest(
        data: HttpRequestData,
        callback: UrlRequest.Callback,
        uploadDataProvider: UploadDataProvider?
    ): UrlRequest {
        return cronetEngine.newUrlRequestBuilder(
            /* url = */ data.url.toString(),
            /* callback = */ callback,
            /* executor = */ executor,
        ).apply {
            setHttpMethod(data.method.value)

            data.headers.flattenForEach { key, value ->
                addHeader(key, value)
            }

            uploadDataProvider?.let {
                setUploadDataProvider(it, executor)
            }

            data.body.contentType?.let {
                addHeader(HttpHeaders.ContentType, "${it.contentType}/${it.contentSubtype}")
            }
        }.build()
    }
}

@OptIn(InternalAPI::class)
private fun UrlResponseInfo.toHttpResponseData(
    method: HttpMethod,
    attributes: Attributes,
    requestTime: GMTDate,
    callContext: CoroutineContext,
    responseBody: ByteReadChannel,
): HttpResponseData {
    return HttpResponseData(
        statusCode = HttpStatusCode.fromValue(httpStatusCode),
        requestTime = requestTime,
        headers = Headers.build {
            allHeaders.forEach { (key, values) ->
                appendAll(key, values)
            }
            dropCompressionHeaders(method, attributes)
        },
        version = when (negotiatedProtocol) {
            "h2" -> HttpProtocolVersion.HTTP_2_0
            "h3" -> HttpProtocolVersion.HTTP_3_0
            "quic/1+spdy/3" -> HttpProtocolVersion.SPDY_3
            else -> HttpProtocolVersion.HTTP_1_1
        },
        body = responseBody,
        callContext = callContext,
    )
}

private suspend fun OutgoingContent.toUploadDataProvider(): UploadDataProvider? {
    return when (val outgoingContent = this) {
        is OutgoingContent.NoContent -> null

        is OutgoingContent.ContentWrapper -> outgoingContent.delegate().toUploadDataProvider()

        is OutgoingContent.ByteArrayContent -> {
            UploadDataProviders.create(outgoingContent.bytes())
        }

        is OutgoingContent.ReadChannelContent -> {
            UploadDataProviders.create(outgoingContent.readFrom().toByteArray())
        }

        is OutgoingContent.WriteChannelContent -> coroutineScope {
            UploadDataProviders.create(toReadChannel(outgoingContent).toByteArray())
        }

        is OutgoingContent.ProtocolUpgrade -> error("UnsupportedContentType $this")
    }
}

private fun CoroutineScope.toReadChannel(content: OutgoingContent.WriteChannelContent): ByteReadChannel {
    return writer(Dispatchers.IO) {
        content.writeTo(channel)
    }.channel
}
