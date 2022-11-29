package org.stellar.walletsdk.exception

sealed class ValidationException : WalletException {
  constructor(message: String) : super(message)
  constructor(message: String, cause: Exception) : super(message, cause)
}

object ClientDomainWithMemoException :
  ValidationException("Client domain cannot be used with memo")

class InvalidAnchorServiceUrl(e: Exception) :
  ValidationException("Anchor service URL is invalid", e)

object InvalidMemoIdException : ValidationException("Memo ID must be a positive integer")

object InvalidStartingBalanceException :
  ValidationException("Starting balance must be at least 1 XLM for non-sponsored accounts")

// Invalid response from server
sealed class InvalidResponseException(message: String) : WalletException(message)

object MissingTokenException : InvalidResponseException("Token was not returned")

object MissingTransactionException :
  InvalidResponseException("The response did not contain a transaction")

object NetworkMismatchException : InvalidResponseException("Networks don't match")
