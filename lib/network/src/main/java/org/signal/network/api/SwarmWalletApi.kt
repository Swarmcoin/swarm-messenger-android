/*
 * Copyright 2026 SWARM
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.signal.network.api

import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.signal.core.util.Base64
import org.signal.core.util.serialization.SignalJson
import org.signal.libsignal.net.BadRequestError
import org.signal.libsignal.net.RequestResult
import org.signal.libsignal.protocol.IdentityKey
import org.signal.network.rest.RequestSpec
import org.signal.network.rest.RestStatusCodeError
import org.signal.network.rest.SignalRestClient
import org.signal.network.rest.bodyString
import org.signal.network.rest.toTypedResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * SWARM addition (wallet sign-in): the two requests that turn possession of a SWARM wallet's identity key into a
 * one-time registration password, so the app can run Signal's own `POST /v1/registration` with no telephone
 * number, no SMS and no captcha. Server side: `SwarmWalletRegistrationController` in
 * Swarm-Official/swarm-messenger-server, `docs/WALLET-SIGN-IN.md`.
 *
 * Both are unauthenticated REST calls to the chat server. What crosses the network is an identity public key and a
 * signature; the recovery phrase, the seed and the private keys never do.
 */
class SwarmWalletApi(private val restClient: SignalRestClient) {

  companion object {
    private val APPLICATION_JSON = "application/json".toMediaType()
  }

  /**
   * `POST /v1/swarm/registration/challenge`
   * - 200: `{ challenge, ttlSeconds }`
   * - 400 / 422: the identity key was missing or malformed
   * - 429: rate limited (20 a minute per address)
   */
  suspend fun requestChallenge(identityKey: IdentityKey): RequestResult<Challenge, SwarmWalletError> {
    val result = restClient.request(
      RequestSpec(
        method = RequestSpec.Method.POST,
        host = RequestSpec.Host.Service,
        path = "/v1/swarm/registration/challenge",
        body = ChallengeRequestBody(identityKey = Base64.encodeWithPadding(identityKey.serialize())).toJsonRequestBody()
      )
    )

    return result.toTypedResult(
      parseSuccess = { response ->
        val body = SignalJson.json.decodeFromString<ChallengeResponseBody>(response.bodyString())
        Challenge(challenge = Base64.decode(body.challenge), ttlSeconds = body.ttlSeconds)
      },
      mapError = { error -> mapError(error) }
    )
  }

  /**
   * `POST /v1/swarm/registration/verify`
   * - 200: `{ number, registrationPassword }`, the account identifier the key derives and a single-use password for it
   * - 403: the signature did not verify
   * - 404: no challenge is pending for this key (expired, already answered, or never issued)
   * - 409: the identifier this key derives already belongs to another identity key
   * - 400 / 422: malformed request
   * - 429: rate limited (6 a minute per address)
   */
  suspend fun verifyChallenge(identityKey: IdentityKey, signature: ByteArray): RequestResult<Verification, SwarmWalletError> {
    val result = restClient.request(
      RequestSpec(
        method = RequestSpec.Method.POST,
        host = RequestSpec.Host.Service,
        path = "/v1/swarm/registration/verify",
        body = VerifyRequestBody(
          identityKey = Base64.encodeWithPadding(identityKey.serialize()),
          signature = Base64.encodeWithPadding(signature)
        ).toJsonRequestBody()
      )
    )

    return result.toTypedResult(
      parseSuccess = { response ->
        val body = SignalJson.json.decodeFromString<VerifyResponseBody>(response.bodyString())
        Verification(number = body.number, registrationPassword = body.registrationPassword)
      },
      mapError = { error -> mapError(error) }
    )
  }

  private fun mapError(error: RestStatusCodeError): SwarmWalletError? {
    return when (error.statusCode) {
      400, 422 -> SwarmWalletError.InvalidRequest(error.bodyString())
      403 -> SwarmWalletError.SignatureRejected
      404 -> SwarmWalletError.NoChallengePending
      409 -> SwarmWalletError.IdentifierTaken
      429 -> SwarmWalletError.RateLimited(error.headers["retry-after"]?.toLongOrNull()?.seconds ?: 0.seconds)
      else -> null
    }
  }

  private inline fun <reified T> T.toJsonRequestBody(): RequestBody {
    return SignalJson.json.encodeToString(this).toRequestBody(APPLICATION_JSON)
  }

  /** 32 random bytes to sign, and how long the server keeps them answerable. */
  class Challenge(val challenge: ByteArray, val ttlSeconds: Long) {
    override fun toString(): String = "Challenge(ttlSeconds=$ttlSeconds)"
  }

  /**
   * @property number the account identifier (`+888...`). Never shown to a person.
   * @property registrationPassword base64, single use, exactly as the server sent it; it is what
   *   `POST /v1/registration` takes as `recoveryPassword`.
   */
  class Verification(val number: String, val registrationPassword: String) {
    override fun toString(): String = "Verification(<redacted>)"
  }

  sealed class SwarmWalletError : BadRequestError {
    data class InvalidRequest(val body: String) : SwarmWalletError()
    data object SignatureRejected : SwarmWalletError()
    data object NoChallengePending : SwarmWalletError()
    data object IdentifierTaken : SwarmWalletError()
    data class RateLimited(val retryAfter: Duration) : SwarmWalletError()
  }

  @Serializable
  private data class ChallengeRequestBody(val identityKey: String)

  @Serializable
  private data class ChallengeResponseBody(val challenge: String, val ttlSeconds: Long)

  @Serializable
  private data class VerifyRequestBody(val identityKey: String, val signature: String)

  @Serializable
  private class VerifyResponseBody(val number: String, val registrationPassword: String) {
    override fun toString(): String = "VerifyResponseBody(<redacted>)"
  }
}
