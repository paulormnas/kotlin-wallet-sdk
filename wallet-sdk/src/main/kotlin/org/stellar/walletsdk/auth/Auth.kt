package org.stellar.walletsdk.auth

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import java.util.*
import kotlinx.datetime.Clock
import mu.KotlinLogging
import org.stellar.sdk.Network
import org.stellar.sdk.Transaction
import org.stellar.walletsdk.Config
import org.stellar.walletsdk.exception.*
import org.stellar.walletsdk.horizon.AccountKeyPair
import org.stellar.walletsdk.util.Util.postJson

private val log = KotlinLogging.logger {}

/**
 * Authenticate to an external server using
 * [SEP-10](https://github.com/stellar/stellar-protocol/blob/master/ecosystem/sep-0010.md).
 */
class Auth
internal constructor(
  private val cfg: Config,
  private val webAuthEndpoint: String,
  private val homeDomain: String,
  private val httpClient: HttpClient
) {
  /**
   * Authenticates to an external server.
   *
   * @param accountAddress Stellar address of the account authenticating
   * @param walletSigner overriding [Auth.defaultSigner] to use in this authentication
   * @param memoId optional memo ID to distinguish the account
   * @param clientDomain optional domain hosting stellar.toml file containing `SIGNING_KEY`
   * @return authentication token (JWT)
   * @throws [ValidationException] when some of the request arguments are not valid
   * @throws [ServerRequestFailedException] when request fails
   * @throws [InvalidResponseException] when JSON response is malformed
   */
  suspend fun authenticate(
    accountAddress: AccountKeyPair,
    walletSigner: WalletSigner? = null,
    memoId: String? = null,
    clientDomain: String? = null
  ): AuthToken {
    val challengeTxn =
      challenge(accountAddress, memoId, clientDomain ?: cfg.app.defaultClientDomain)
    val signedTxn = sign(accountAddress, challengeTxn, walletSigner ?: cfg.app.defaultSigner)
    return getToken(signedTxn)
  }

  /**
   * Request transaction challenge from the auth endpoint.
   *
   * @return transaction as Base64 encoded TransactionEnvelope XDR string and network passphrase
   * from the auth endpoint
   * @throws [InvalidMemoIdException] when memo ID is not valid
   * @throws [ClientDomainWithMemoException] when both client domain and memo ID provided
   * @throws [ServerRequestFailedException] when request fails
   * @throws [InvalidResponseException] when JSON response is malformed
   */
  @Suppress("ThrowsCount")
  private suspend fun challenge(
    account: AccountKeyPair,
    memoId: String? = null,
    clientDomain: String? = null
  ): ChallengeResponse {
    val url = URLBuilder(webAuthEndpoint)

    // Add required query params
    url.parameters.append("account", account.address)
    url.parameters.append("home_domain", homeDomain)

    if (!memoId.isNullOrBlank()) {
      if (memoId.toInt() < 0) {
        throw InvalidMemoIdException
      }

      url.parameters.append("memo", memoId)
    }

    if (!clientDomain.isNullOrBlank()) {
      if (!memoId.isNullOrBlank()) {
        throw ClientDomainWithMemoException
      }

      url.parameters.append("client_domain", clientDomain)
    }

    log.debug {
      "Challenge request: account = $account, memo = $memoId, client_domain = $clientDomain"
    }

    val jsonResponse = httpClient.get(url.build()).body<ChallengeResponse>()

    if (jsonResponse.transaction.isBlank()) {
      throw MissingTransactionException
    }

    if (jsonResponse.networkPassphrase != cfg.stellar.network.networkPassphrase) {
      throw NetworkMismatchException
    }

    return jsonResponse
  }

  /**
   * Sign transaction with client account and, optionally, domain account using [WalletSigner]
   * methods.
   *
   * @param challengeResponse challenge transaction and network passphrase returned from the auth
   * endpoint
   * @return signed transaction
   */
  private suspend fun sign(
    account: AccountKeyPair,
    challengeResponse: ChallengeResponse,
    walletSigner: WalletSigner
  ): Transaction {
    var challengeTxn =
      Transaction.fromEnvelopeXdr(
        challengeResponse.transaction,
        Network(challengeResponse.networkPassphrase)
      ) as Transaction

    val clientDomainOperation =
      challengeTxn.operations.firstOrNull {
        it.toXdr().body?.manageDataOp?.dataName?.string64.toString() == "client_domain"
      }

    if (clientDomainOperation != null) {
      log.debug { "Authenticating with client domain" }

      challengeTxn =
        walletSigner.signWithDomainAccount(
          challengeResponse.transaction,
          challengeResponse.networkPassphrase,
          account
        )
    }

    walletSigner.signWithClientAccount(challengeTxn, account)

    return challengeTxn
  }

  /**
   * Send signed transaction to the auth endpoint to get JWT token.
   *
   * @param signedTransaction signed transaction
   * @return transaction as Base64 encoded TransactionEnvelope XDR string
   * @throws [ServerRequestFailedException] when request fails
   * @throws [MissingTokenException] when request JSON response does not contain `token`
   */
  private suspend fun getToken(signedTransaction: Transaction): AuthToken {
    val signedChallengeTxnXdr = signedTransaction.toEnvelopeXdrBase64()
    val tokenRequestParams = AuthTransaction(signedChallengeTxnXdr)

    val resp: AuthTokenResponse = httpClient.postJson(webAuthEndpoint, tokenRequestParams)

    if (resp.token.isBlank()) {
      throw MissingTokenException
    }

    val token = AuthToken.from(resp.token)

    if (token.expiresAt < Clock.System.now()) {
      throw ValidationException(
        "Auth token has already expired. Expiration time: ${token.expiresAt}"
      )
    }

    return token
  }
}
