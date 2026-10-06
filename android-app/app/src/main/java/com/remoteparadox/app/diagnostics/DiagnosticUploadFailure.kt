package com.remoteparadox.app.diagnostics

import java.io.IOException
import java.io.InterruptedIOException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

internal enum class DiagnosticUploadFailure(val message: String, val suggestManualRetry: Boolean = true) {
    MISSING_PIN("Pinned certificate is missing. Use the trusted Pi setup QR code, then collect a new report.", false),
    MALFORMED_PIN("Pinned certificate is invalid. Use the trusted Pi setup QR code, then collect a new report.", false),
    INVALID_ENDPOINT("Diagnostics requires a valid HTTPS server address with a pinned certificate."),
    CERTIFICATE_MISMATCH("Server certificate does not match the trusted pin. Verify the Pi setup before retrying."),
    TLS_FAILURE("Secure connection failed. Verify the Pi setup before retrying."),
    AUTHORIZATION_FAILED("Server denied diagnostic upload (401/403). Check sign-in and diagnostic permissions."),
    ENDPOINT_UNAVAILABLE("Diagnostic endpoint unavailable (404). Check that the Pi bridge supports diagnostics."),
    RATE_LIMITED("Diagnostic upload rate limited (429). Wait before retrying manually."),
    REPORT_REJECTED("Server rejected the diagnostic report (422/413). Check Pi bridge compatibility."),
    REDIRECT_BLOCKED("Diagnostic upload redirect blocked. Check the Pi server address."),
    SERVER_REJECTED("Server did not accept the diagnostic upload."),
    TIMEOUT("Diagnostic upload timed out. Receipt not confirmed; check connectivity before retrying."),
    NETWORK("Diagnostic server could not be reached or the connection was interrupted. Receipt not confirmed."),
    MALFORMED_RECEIPT("Server receipt was missing or invalid. Upload not confirmed."),
    UNKNOWN("Diagnostic upload failed. Receipt not confirmed."),
}

internal class DiagnosticUploadException(val failure: DiagnosticUploadFailure) : IOException(failure.message)

internal class DiagnosticCertificateMismatchException : CertificateException("Diagnostic certificate mismatch")

internal fun diagnosticHttpFailure(status: Int): DiagnosticUploadFailure = when (status) {
    401, 403 -> DiagnosticUploadFailure.AUTHORIZATION_FAILED
    404 -> DiagnosticUploadFailure.ENDPOINT_UNAVAILABLE
    429 -> DiagnosticUploadFailure.RATE_LIMITED
    422, 413 -> DiagnosticUploadFailure.REPORT_REJECTED
    in 300..399 -> DiagnosticUploadFailure.REDIRECT_BLOCKED
    else -> DiagnosticUploadFailure.SERVER_REJECTED
}

internal fun diagnosticUploadFailure(error: Throwable): DiagnosticUploadFailure {
    // TLS libraries can wrap the pin failure. Never inspect or display exception text.
    val causes = generateSequence(error) { it.cause }.take(16).toList()
    causes.filterIsInstance<DiagnosticUploadException>().firstOrNull()?.let { return it.failure }
    return when {
        causes.any { it is DiagnosticCertificateMismatchException || it is SSLPeerUnverifiedException } ->
            DiagnosticUploadFailure.CERTIFICATE_MISMATCH
        causes.any { it is SSLException || it is CertificateException } -> DiagnosticUploadFailure.TLS_FAILURE
        causes.any { it is InterruptedIOException } -> DiagnosticUploadFailure.TIMEOUT
        causes.any { it is IOException } -> DiagnosticUploadFailure.NETWORK
        else -> DiagnosticUploadFailure.UNKNOWN
    }
}

internal fun DiagnosticUploadFailure.savedCaptureMessage(): String =
    "$message Saved captures expire 24 hours after collection." + if (suggestManualRetry) " Retry manually." else ""
