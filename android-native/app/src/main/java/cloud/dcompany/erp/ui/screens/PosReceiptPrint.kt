package cloud.dcompany.erp.ui.screens

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
import cloud.dcompany.erp.core.db.PosReceiptEntity
import cloud.dcompany.erp.core.db.decodedLines
import cloud.dcompany.erp.core.net.asRupees
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opens Android's system print destination for a stored, immutable receipt.
 * The operator can select a configured printer or Save as PDF. Nothing is sent
 * to an external service and JavaScript stays disabled.
 */
internal fun launchPosReceiptPrint(
    context: Context,
    receipt: PosReceiptEntity,
    onLaunched: () -> Unit,
    onFailure: (String) -> Unit,
) {
    val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
    if (printManager == null) {
        onFailure("Printing is not available on this device. The receipt is still saved in POS.")
        return
    }

    val webView = WebView(context).apply {
        settings.javaScriptEnabled = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
    }
    val submitted = AtomicBoolean(false)
    webView.webViewClient = object : WebViewClient() {
        override fun onPageFinished(view: WebView, url: String?) {
            if (!submitted.compareAndSet(false, true)) return
            runCatching {
                val invoice = receipt.invoiceNo?.takeIf(String::isNotBlank)
                    ?: receipt.orderId.take(8)
                val jobName = "D Company receipt $invoice"
                printManager.print(
                    jobName,
                    view.createPrintDocumentAdapter(jobName),
                    PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.UNKNOWN_PORTRAIT)
                        .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                        .build(),
                )
            }.onSuccess {
                onLaunched()
            }.onFailure {
                onFailure(
                    "The print screen could not open. The receipt is still saved and can be reopened from POS.",
                )
            }
        }
    }
    webView.loadDataWithBaseURL(
        null,
        posReceiptPrintHtml(receipt),
        "text/html",
        "UTF-8",
        null,
    )
}

internal fun posReceiptPrintHtml(receipt: PosReceiptEntity): String {
    val lines = receipt.decodedLines().joinToString(separator = "") { line ->
        val customizations = buildList {
            line.variantSnapshot?.name?.takeIf(String::isNotBlank)?.let(::add)
            line.modifiers.orEmpty().forEach { modifier ->
                add(
                    if (modifier.qty == 1) modifier.name
                    else "${modifier.name} x${modifier.qty}",
                )
            }
            line.note?.takeIf(String::isNotBlank)?.let { add("Note: $it") }
        }.joinToString(" · ")
        """
        <tr>
          <td>${line.qty.receiptQuantity()} x ${line.name.escapeReceiptHtml()}
            ${customizations.takeIf(String::isNotBlank)?.let { "<small>${it.escapeReceiptHtml()}</small>" }.orEmpty()}
          </td>
          <td class="money">${line.lineTotalMinor.asRupees().escapeReceiptHtml()}</td>
        </tr>
        """.trimIndent()
    }
    val customer = listOfNotNull(receipt.customerName, receipt.customerPhone)
        .joinToString(" · ")
        .takeIf(String::isNotBlank)
    val reference = receipt.refExternal?.takeIf(String::isNotBlank)
    val paymentTime = receipt.paidAt?.takeIf(String::isNotBlank)
        ?: receipt.invoiceIssuedAt?.takeIf(String::isNotBlank)

    return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <style>
            @page { margin: 10mm; }
            body { color: #111; font-family: sans-serif; font-size: 12px; margin: 0 auto; max-width: 78mm; }
            h1 { font-size: 19px; letter-spacing: 0.5px; margin: 0; text-align: center; }
            .center { text-align: center; }
            .muted { color: #555; font-size: 10px; }
            .rule { border-top: 1px dashed #555; margin: 9px 0; }
            table { border-collapse: collapse; width: 100%; }
            td { padding: 4px 0; vertical-align: top; }
            td.money { font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
            small { color: #555; display: block; font-size: 9px; margin-top: 2px; }
            .total { font-size: 15px; font-weight: 700; }
          </style>
        </head>
        <body>
          <h1>D COMPANY</h1>
          <div class="center muted">Payment receipt</div>
          <div class="rule"></div>
          <div>${(receipt.invoiceNo ?: "Order ${receipt.orderId.take(8)}").escapeReceiptHtml()}</div>
          ${receipt.sourceLabel?.takeIf(String::isNotBlank)?.let { "<div>${it.escapeReceiptHtml()}</div>" }.orEmpty()}
          ${customer?.let { "<div>${it.escapeReceiptHtml()}</div>" }.orEmpty()}
          ${paymentTime?.let { "<div class=\"muted\">${it.escapeReceiptHtml()}</div>" }.orEmpty()}
          <div class="rule"></div>
          <table>$lines</table>
          <div class="rule"></div>
          <table>
            <tr><td>Subtotal</td><td class="money">${receipt.subtotalMinor.asRupees().escapeReceiptHtml()}</td></tr>
            ${receipt.discountMinor.takeIf { it > 0 }?.let { "<tr><td>Discount</td><td class=\"money\">-${it.asRupees().escapeReceiptHtml()}</td></tr>" }.orEmpty()}
            ${receipt.taxMinor.takeIf { it != 0L }?.let { "<tr><td>Tax</td><td class=\"money\">${it.asRupees().escapeReceiptHtml()}</td></tr>" }.orEmpty()}
            ${receipt.roundOffMinor.takeIf { it != 0L }?.let { "<tr><td>Round-off</td><td class=\"money\">${it.asRupees().escapeReceiptHtml()}</td></tr>" }.orEmpty()}
            <tr class="total"><td>Total</td><td class="money">${receipt.totalMinor.asRupees().escapeReceiptHtml()}</td></tr>
            <tr><td>Paid · ${receipt.method.paymentMethodLabel().escapeReceiptHtml()}</td><td class="money">${receipt.amountMinor.asRupees().escapeReceiptHtml()}</td></tr>
            ${receipt.tenderedMinor?.let { "<tr><td>Cash received</td><td class=\"money\">${it.asRupees().escapeReceiptHtml()}</td></tr>" }.orEmpty()}
            ${receipt.changeMinor?.let { "<tr><td>Change</td><td class=\"money\">${it.asRupees().escapeReceiptHtml()}</td></tr>" }.orEmpty()}
          </table>
          ${receipt.orderNote?.takeIf(String::isNotBlank)?.let { "<div class=\"rule\"></div><div>Note: ${it.escapeReceiptHtml()}</div>" }.orEmpty()}
          ${reference?.let { "<div class=\"muted\">Reference: ${it.escapeReceiptHtml()}</div>" }.orEmpty()}
          <div class="rule"></div>
          <div class="center muted">Thank you</div>
        </body>
        </html>
    """.trimIndent()
}

private fun Double.receiptQuantity(): String =
    if (this % 1.0 == 0.0) toLong().toString() else toString()

private fun String.escapeReceiptHtml(): String = buildString(length) {
    this@escapeReceiptHtml.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            },
        )
    }
}
