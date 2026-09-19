package cloud.dcompany.erp.ui.screens.finance

import androidx.core.content.FileProvider

/** Dedicated provider identity so directRelease can also keep its separately
 * scoped, update-package FileProvider without manifest-merger collisions. */
class ExpenseReceiptFileProvider : FileProvider()
