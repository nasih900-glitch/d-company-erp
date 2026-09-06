package cloud.dcompany.erp.core.sync

import cloud.dcompany.erp.core.net.ApiException

/** A decoder or local persistence failure can follow an already committed write. */
internal fun mustReplayUnconfirmedWrite(failure: Exception): Boolean =
    failure !is ApiException || failure.mustPreserveOutbox

internal fun unconfirmedWriteMessage(subject: String): String =
    "Could not confirm $subject yet. It may already be saved on the server. " +
        "The original request is kept for safe retry. Do not repeat it or collect payment again; " +
        "keep this tablet online, and use Help if it remains waiting."
