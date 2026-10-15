package com.unciv.iaciv.net

import com.unciv.iaciv.dto.EventsRequest
import com.unciv.iaciv.dto.HealthResponse
import com.unciv.iaciv.dto.OrdersResponse
import com.unciv.iaciv.dto.PROTOCOL_VERSION
import com.unciv.iaciv.dto.PendingResponse
import com.unciv.iaciv.dto.SessionRequest
import com.unciv.iaciv.dto.SessionResponse
import com.unciv.iaciv.dto.TurnEndRequest
import com.unciv.iaciv.dto.TurnStartRequest
import com.unciv.iaciv.dto.iaCivJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Blocking HTTP client of the bridge. Every call has a hard deadline: the game must never wait forever.
 * Called from the turn thread (never the GL thread), so `runBlocking` is fine (architecture.md §4).
 */
class OrchestratorClient(baseUrl: String, private val bridgeVersion: String) : Closeable {
    private val baseUrl = baseUrl.trimEnd('/')

    private val http = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout)
        engine {
            requestTimeout = 0 // CIO's own 15 s default would cut council long-polling; HttpTimeout handles deadlines
        }
    }

    /** Fallback reasons of contract §6: timeout, invalid_response, orchestrator_down (budget_exhausted/disabled are decided elsewhere) */
    sealed class Outcome<out T> {
        data class Ok<T>(val value: T) : Outcome<T>()
        data class Failed(val reason: String, val detail: String) : Outcome<Nothing>()
    }

    fun health(timeoutS: Int, gameId: String): Outcome<HealthResponse> = call(timeoutS) {
        val response = http.get("$baseUrl/v1/health") { commonHeaders(gameId); timeoutMs(timeoutS) }
        decode(response, HealthResponse.serializer())
    }

    fun session(request: SessionRequest, timeoutS: Int): Outcome<SessionResponse> = call(timeoutS) {
        val response = post("/v1/session", request.gameId, timeoutS, iaCivJson.encodeToString(SessionRequest.serializer(), request))
        decode(response, SessionResponse.serializer())
    }

    /**
     * `POST /v1/turn-start`: `200` -> orders; `202` -> long-poll `GET /v1/orders/{ticket}` until `200` or [councilTimeoutS].
     * Connection refused: one retry after 2 s (contract §8).
     */
    fun turnStart(body: String, request: TurnStartRequest, turnStartTimeoutS: Int, councilTimeoutS: Int, pollWaitS: Int): Outcome<OrdersResponse> {
        val first = call(turnStartTimeoutS, retryWhenDown = true) {
            val response = post("/v1/turn-start", request.gameId, turnStartTimeoutS, body)
            when (response.status.value) {
                200 -> TurnStartReply.Orders(decode(response, OrdersResponse.serializer()))
                202 -> TurnStartReply.Pending(decode(response, PendingResponse.serializer()))
                409 -> throw NoSession(response.bodyAsText().take(300)) // orchestrator restarted: the session must be reopened
                else -> throw InvalidResponse("HTTP ${response.status.value} : ${response.bodyAsText().take(300)}")
            }
        }
        val reply = when (first) {
            is Outcome.Failed -> return first
            is Outcome.Ok -> first.value
        }
        if (reply is TurnStartReply.Orders) return Outcome.Ok(reply.orders)

        val ticket = (reply as TurnStartReply.Pending).pending.ticket
        val deadline = System.currentTimeMillis() + councilTimeoutS * 1000L
        while (true) {
            val remainingS = ((deadline - System.currentTimeMillis()) / 1000).toInt()
            if (remainingS <= 0) return Outcome.Failed("timeout", "conseil non terminé après $councilTimeoutS s (ticket $ticket)")
            val waitS = minOf(pollWaitS, remainingS)
            val poll = call(waitS + 10) {
                val response = http.get("$baseUrl/v1/orders/$ticket?wait=$waitS") {
                    commonHeaders(request.gameId); timeoutMs(waitS + 10)
                }
                when (response.status.value) {
                    200 -> decode(response, OrdersResponse.serializer())
                    204 -> null
                    else -> throw InvalidResponse("HTTP ${response.status.value} sur /v1/orders/$ticket")
                }
            }
            when (poll) {
                is Outcome.Failed -> if (poll.reason != "timeout") return poll // a slow poll is retried until the deadline
                is Outcome.Ok -> poll.value?.let { return Outcome.Ok(it) }
            }
        }
    }

    fun turnEnd(request: TurnEndRequest, timeoutS: Int): Outcome<Unit> = call(timeoutS) {
        val response = post("/v1/turn-end", request.gameId, timeoutS, iaCivJson.encodeToString(TurnEndRequest.serializer(), request))
        if (response.status.value !in 200..299) throw InvalidResponse("HTTP ${response.status.value}")
    }

    fun events(request: EventsRequest, timeoutS: Int): Outcome<Unit> = call(timeoutS) {
        val response = post("/v1/events", request.gameId, timeoutS, iaCivJson.encodeToString(EventsRequest.serializer(), request))
        if (response.status.value !in 200..299) throw InvalidResponse("HTTP ${response.status.value}")
    }

    override fun close() = http.close()

    companion object {
        /** Internal reason (not a contract fallback reason): the bridge reopens the session and retries once */
        const val NO_SESSION = "no_session"
    }

    // region internals

    private sealed class TurnStartReply {
        data class Orders(val orders: OrdersResponse) : TurnStartReply()
        data class Pending(val pending: PendingResponse) : TurnStartReply()
    }

    private class InvalidResponse(message: String) : Exception(message)
    private class NoSession(message: String) : Exception(message)

    private suspend fun post(path: String, gameId: String, timeoutS: Int, body: String): HttpResponse =
        http.post("$baseUrl$path") {
            commonHeaders(gameId)
            contentType(ContentType.Application.Json)
            setBody(body)
            timeoutMs(timeoutS)
        }

    private fun HttpRequestBuilder.commonHeaders(gameId: String) {
        header("X-IaCiv-Protocol", PROTOCOL_VERSION.toString())
        header("X-IaCiv-Game", gameId)
        header("X-IaCiv-Bridge", bridgeVersion)
    }

    private fun HttpRequestBuilder.timeoutMs(timeoutS: Int) = timeout {
        requestTimeoutMillis = timeoutS * 1000L
        connectTimeoutMillis = minOf(timeoutS, 5) * 1000L
    }

    private suspend fun <T> decode(response: HttpResponse, serializer: KSerializer<T>): T {
        val text = response.bodyAsText()
        return try {
            iaCivJson.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            throw InvalidResponse("JSON invalide (${e.message?.take(200)}) : ${text.take(200)}")
        } catch (e: IllegalArgumentException) {
            throw InvalidResponse("JSON invalide (${e.message?.take(200)}) : ${text.take(200)}")
        }
    }

    private fun <T> call(timeoutS: Int, retryWhenDown: Boolean = false, block: suspend () -> T): Outcome<T> {
        var outcome = attempt(timeoutS, block)
        if (retryWhenDown && outcome is Outcome.Failed && outcome.reason == "orchestrator_down") {
            runBlocking { delay(2000) }
            outcome = attempt(timeoutS, block)
        }
        return outcome
    }

    private fun <T> attempt(timeoutS: Int, block: suspend () -> T): Outcome<T> = try {
        Outcome.Ok(runBlocking { withTimeout(timeoutS * 1000L + 1000L) { block() } })
    } catch (e: TimeoutCancellationException) {
        Outcome.Failed("timeout", "pas de réponse en $timeoutS s")
    } catch (e: HttpRequestTimeoutException) {
        Outcome.Failed("timeout", e.message ?: "délai dépassé")
    } catch (e: SocketTimeoutException) {
        Outcome.Failed("timeout", e.message ?: "délai dépassé")
    } catch (e: InvalidResponse) {
        Outcome.Failed("invalid_response", e.message ?: "réponse invalide")
    } catch (e: NoSession) {
        Outcome.Failed(NO_SESSION, e.message ?: "session inconnue")
    } catch (e: IOException) {
        Outcome.Failed("orchestrator_down", "${e::class.simpleName}: ${e.message}")
    } catch (e: Exception) {
        Outcome.Failed("orchestrator_down", "${e::class.simpleName}: ${e.message}")
    }

    // endregion
}
