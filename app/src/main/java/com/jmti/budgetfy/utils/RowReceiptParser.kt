package com.jmti.budgetfy.utils

import com.jmti.budgetfy.models.ReceiptCharge
import com.jmti.budgetfy.models.ReceiptItem
import kotlin.math.abs
import kotlin.math.round

/**
 * Offline receipt reader that works on OCR *rows* ("NAME   QTY   PRICE"),
 * the way a person reads a receipt: name on the left, price on the right.
 *
 * - Item rows end with a price; a row without a price either continues the
 *   item's name ("Chicken & Chicken") or, before a name-less price row
 *   ("1X 39.90-(4.90)C = 35.00"), is that item's name.
 * - Modifier rows (":Level 1  0.00", "+ Brownie 0.60", "• Less sugar") are
 *   part of the item above and are skipped.
 * - The item list stops at the totals; service charge, tax and rounding are
 *   read from there, and the total is the amount that best matches.
 */
class RowReceiptParser(private val parser: ReceiptParser = ReceiptParser()) {

    private val categorizationEngine = CategorizationEngine()

    // A price at the end of a row, possibly negative or written ".01".
    private val trailingPrice = Regex("(-?\\s?(?:\\d{1,4})?\\.\\s?\\d{2})(-)?\\s*(?:SR|ZR|SV|S|Z|T|E|X|\\*)?\\s*$")
    // Supermarket item barcode (EAN-13 and similar) at the start of an item line.
    private val barcode = Regex("\\b\\d{12,14}\\b")
    // Price details: "7.460 kg @ 6.89/kg", "2 @ 14.99", "(11.20/ea)"; not "OIL 1KG".
    private val detailRow = Regex("@|/\\s*(kg|ea|pc|pcs)\\b|^\\s*\\d+(\\.\\d+)?\\s*(kg|g|ml)\\b", RegexOption.IGNORE_CASE)
    // Promotions and discounts inside the item list ("BUY 3 FOR RM 18", "Member disc").
    private val promoWords = Regex(
        "\\b(discount|disc|diskaun|promo\\w*|offer|voucher|coupon|saving|savings|member\\s*price|less)\\b|\\bbuy\\s*\\d+\\s*(for|free)\\b",
        RegexOption.IGNORE_CASE
    )
    // Column header words, loose enough for OCR slips ("Quàntily", "Sub-iotal").
    private val headerRow = Regex(
        "\\b(item|name|description|desc|qty|quan\\w*|pr\\w?ice|amount|amt|sub-?\\s?\\w{0,2}otal|sub-\\w+)\\b",
        RegexOption.IGNORE_CASE
    )
    private val metadataRow = Regex(
        "(\\b(invoice|receipt|bill|order|table|cashier|counter|pos|date|time|tel|phone|fax|tax id|sst id|gst id|reg|no\\.?)\\b\\s*[:#]?)" +
                "|\\d{1,2}/\\d{1,2}/\\d{2,4}|\\d{1,2}:\\d{2}|www\\.|\\bsdn\\b|\\bbhd\\b|\\bjalan\\b|\\btaman\\b|\\blot\\b|\\d{5}\\s*,",
        RegexOption.IGNORE_CASE
    )
    private val totalWords = Regex("\\b(grand\\s*total|total\\s*a[mn]+ount|total\\s*due|amount\\s*due|net\\s*total|nett|total|jumlah)\\b", RegexOption.IGNORE_CASE)
    private val notTotal = Regex("\\b(discount|qty|quantity|items?|products?|saving|savings|sub\\s*-?\\s*total|subtotal)\\b", RegexOption.IGNORE_CASE)
    private val subtotalWords = Regex("\\b(sub\\s*-?\\s*total|subtotal)\\b", RegexOption.IGNORE_CASE)
    private val paymentWords = Regex("\\b(master\\s*card|mastercard|visa|card|debit|credit|duitnow|tng|touch\\s*n\\s*go|grabpay|boost|shopeepay|e-?wallet|qr)\\b", RegexOption.IGNORE_CASE)
    private val roundingWords = Regex("\\bround", RegexOption.IGNORE_CASE)
    private val chargeWords = Regex("\\b(service\\s*charge|service\\s*tax|sst|gst|tax|cukai)\\b", RegexOption.IGNORE_CASE)
    private val endWords = Regex("\\b(change|tendered|thank|terima kasih|cash)\\b", RegexOption.IGNORE_CASE)
    private val modifierStart = Regex("^\\s*[:+•*·\\-~>]")

    data class Result(
        val items: List<ReceiptItem>,
        val total: Double?,
        val charges: List<ReceiptCharge>,
        /** Added charges + rounding: what the total should be above the items. */
        val extras: Double
    )

    fun parse(layoutText: String): Result {
        val rows = layoutText.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // Start after the column header if there is one.
        var start = rows.indexOfFirst { headerRow.findAll(it).count() >= 2 && trailingPriceOf(it) == null }
        start = if (start >= 0) start + 1 else 0

        val items = mutableListOf<ReceiptItem>()
        val pending = mutableListOf<String>()   // name rows waiting for a price
        var lastItemOpen = false                // name rows may still extend the last item
        var appended = 0
        var inModifiers = false
        var itemDiscount = 0.0                  // promotions listed between the items
        var end = rows.size

        for (i in start until rows.size) {
            var row = rows[i]
            // Long receipts held in the hand: OCR can merge the previous item's
            // weight line into the next item ("1.431 kg @ 5.99/kg  2124050005070
            // THAI LONGAN  5.07"). Start the row at the item's barcode.
            barcode.find(row)?.let { b ->
                if (b.range.first > 0 && detailRow.containsMatchIn(row.substring(0, b.range.first))) {
                    row = row.substring(b.range.first)
                }
            }
            var price = trailingPriceOf(row)
            var beforePrice = if (price != null) row.substring(0, price.second).trim() else row

            if (isTotalsRow(row)) { end = i; break }

            // "GS COOKING OIL 1KG PA   2@ 14.99": a unit price ("@"), not this
            // line's total, which comes on the next row. Treat as a name row.
            if (price != null && price.first > 0.0 && Regex("\\d\\s*@\\s*$").containsMatchIn(beforePrice)) {
                row = beforePrice.replace(Regex("\\d+\\s*@\\s*$"), "").trim()
                price = null
                beforePrice = row
            }

            // "BUY 3 FOR RM 18   -RM2.94": a discount, not an item.
            if (price != null && price.first != 0.0 &&
                (price.first < 0.0 || promoWords.containsMatchIn(beforePrice))) {
                itemDiscount += abs(price.first)
                pending.clear()
                lastItemOpen = false
                continue
            }

            if (modifierStart.containsMatchIn(row) || (price != null && price.first == 0.0)) {
                inModifiers = true
                lastItemOpen = false
                pending.clear()
                continue
            }

            if (price == null) {
                if (metadataRow.containsMatchIn(row)) { pending.clear(); lastItemOpen = false; continue }
                if (!row.any { it.isLetter() }) continue
                if (inModifiers) continue // e.g. "side" wrapping from ":All Topping on"
                // Price details under an item: "7.460 kg @ 6.89/kg", "2 @ 14.99", "(11.20/ea)".
                if (detailRow.containsMatchIn(row)) continue
                // A row starting with a barcode is a new item, never the end of the last name.
                val newItem = barcode.find(row)?.range?.first == 0
                if (lastItemOpen && appended < 3 && pending.isEmpty() && !newItem) {
                    val last = items.removeAt(items.lastIndex)
                    items.add(last.copy(name = "${last.name} ${cleanName(row)}".trim()))
                    appended++
                } else {
                    pending.add(row)
                }
                continue
            }

            // A row with a price.
            inModifiers = false
            val (qty, namePart) = splitQuantity(beforePrice)
            val ownName = cleanName(namePart)
            // No name on the price row: use the closest name line above it
            // (skipping column headers and short variant lines like "STONE M").
            val name = when {
                ownName.count { it.isLetter() } >= 3 -> ownName
                pending.isNotEmpty() -> pending
                    .filter { headerRow.findAll(it).count() < 2 }
                    .map { cleanName(it) }
                    .lastOrNull { n -> n.count { c -> c.isLetter() } >= 8 }
                    ?: pending.map { cleanName(it) }.lastOrNull { n -> n.count { c -> c.isLetter() } >= 3 }
                    ?: ""
                else -> ""
            }
            pending.clear()
            if (name.count { it.isLetter() } < 3 || metadataRow.containsMatchIn(name)) {
                lastItemOpen = false
                continue // a summary row like "2   9.80   70.00"
            }
            items.add(
                ReceiptItem(
                    name = name,
                    quantity = qty.toString(),
                    price = price.first,
                    category = categorizationEngine.categorizeItem(name)
                )
            )
            lastItemOpen = ownName.count { it.isLetter() } >= 3
            appended = 0
        }

        // ── Quantities from "N @ price" lines ──
        // Supermarkets print "8 @ 6.49" under an item (OCR may merge it into a
        // neighbouring row). Give quantity N to the item whose price is N × price.
        val multiples = rows.subList(start, end).flatMap { r ->
            Regex("(?<![\\d.])(\\d{1,3})\\s*@\\s*(\\d{1,4}\\.\\d{2})(?!\\s*/)").findAll(r).map {
                it.groupValues[1].toInt() to it.groupValues[2].toDouble()
            }.toList()
        }
        for ((n, unit) in multiples) {
            if (n < 2) continue
            val idx = items.indexOfFirst { it.quantity == "1" && abs(it.price - n * unit) <= 0.02 }
            if (idx >= 0) items[idx] = items[idx].copy(quantity = n.toString())
        }

        // ── Totals section ──
        // Each totals row: its label (if any) and its number (if any).
        val labels = mutableListOf<String>()       // labelled rows, top to bottom
        val labelValues = mutableListOf<Double?>() // the number on the same row
        val values = mutableListOf<Double>()       // every number, top to bottom
        for (i in end until rows.size) {
            val row = rows[i]
            if (endWords.containsMatchIn(row) && !paymentWords.containsMatchIn(row) && !paymentEnd.containsMatchIn(row)) continue
            val price = totalsPriceOf(row)
            if (price != null) values.add(price.first)
            val label = if (price != null) row.substring(0, price.second).trim() else row
            if (label.any { it.isLetter() } && labelKind(label) != null) {
                labels.add(label)
                labelValues.add(price?.first)
            }
        }

        val itemsSum = items.sumOf { it.price }
        // Normal case: each label with the number on its own row.
        var best = readTotals(labels, labelValues, itemsSum, values, itemDiscount)
        // Tilted photo: the numbers can drift a row or two from their labels.
        // Line the labels up against the numbers at small offsets and keep
        // the first pairing where items + charges = total.
        if (!best.addsUp) {
            for (shift in listOf(0, -1, 1, -2, 2)) {
                val paired = labels.indices.map { values.getOrNull(it + shift) }
                val attempt = readTotals(labels, paired, itemsSum, values, itemDiscount)
                if (attempt.addsUp) { best = attempt; break }
            }
        }
        return Result(items, best.total, best.charges, best.extras)
    }

    private class Totals(val total: Double?, val charges: List<ReceiptCharge>, val extras: Double, val addsUp: Boolean)

    /** What kind of totals line a label is, or null if it's none. */
    private fun labelKind(label: String): String? = when {
        // "Tax ID: W10-…", "SST Reg No" are registration numbers, not amounts.
        Regex("\\b(id|reg|no)\\b|:", RegexOption.IGNORE_CASE).containsMatchIn(label) &&
                !roundingWords.containsMatchIn(label) && !totalWords.containsMatchIn(label) -> null
        // Headings like "TAX INVOICE" / "Official Receipt".
        Regex("\\b(invoice|receipt|bill)\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) -> null
        roundingWords.containsMatchIn(label) -> "rounding"
        subtotalWords.containsMatchIn(label) -> "subtotal"
        chargeWords.containsMatchIn(label) && !totalWords.containsMatchIn(label) -> "charge"
        totalWords.containsMatchIn(label) && Regex("\\bdiscount\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) -> "discount"
        totalWords.containsMatchIn(label) && !notTotal.containsMatchIn(label) -> "total"
        paymentWords.containsMatchIn(label) || paymentEnd.containsMatchIn(label) -> "payment"
        else -> null
    }

    /**
     * Reads charges, rounding and the total from [labels] paired with [nums].
     * [allValues] are every number in the totals area: one that equals
     * items + charges also counts as the total (e.g. the big total at the
     * bottom with no label of its own).
     */
    private fun readTotals(
        labels: List<String>, nums: List<Double?>, itemsSum: Double, allValues: List<Double>, itemDiscount: Double
    ): Totals {
        val charges = mutableListOf<ReceiptCharge>()
        var rounding = 0.0
        val totals = mutableListOf<Double>()
        val payments = mutableListOf<Double>()
        var subtotal: Double? = null
        var totalsDiscount = 0.0
        labels.forEachIndexed { i, label ->
            val v = nums.getOrNull(i) ?: return@forEachIndexed
            val lower = label.lowercase()
            when (labelKind(label)) {
                "rounding" -> if (abs(v) < 0.10) rounding = v
                "subtotal" -> subtotal = v
                "charge" -> if (v > 0.0) {
                    val inclusive = lower.contains("inclu") || lower.contains("incl.") || lower.contains("termasuk")
                    charges.add(ReceiptCharge(type = chargeType(lower), rate = rateOf(label), amount = v, inclusive = inclusive))
                }
                "total" -> totals.add(v)
                // "Total Discount 2.94": used when the item list didn't already show it.
                "discount" -> if (itemDiscount == 0.0 && abs(v) > 0.0) totalsDiscount = abs(v)
                "payment" -> payments.add(v)
            }
        }
        // A whole-bill discount is kept as a minus "Discount" charge.
        val discount = if (itemDiscount > 0.0) itemDiscount else totalsDiscount
        if (discount > 0.0) charges.add(ReceiptCharge(type = "Discount", amount = -discount))
        val added = charges.filter { !it.inclusive }.sumOf { it.amount }
        val expected = round((itemsSum + added + rounding) * 100) / 100
        val unlabelled = allValues.filter { abs(it - expected) <= 0.06 && it > 0.0 }
        val candidates = (totals.ifEmpty { payments } + unlabelled).filter { it > 0.0 }
        // The printed total that best fits what was read (several "total" lines are common).
        val total = candidates.minByOrNull { abs(it - expected) } ?: subtotal
        val addsUp = total != null && abs(total - expected) <= 0.06 && itemsSum > 0.0
        return Totals(total, charges, added + rounding, addsUp)
    }

    /**
     * Like [trailingPriceOf], plus forms only seen in the totals area:
     * "3 98" (space for the dot) and ".00" / "00".
     */
    private fun totalsPriceOf(row: String): Pair<Double, Int>? {
        trailingPriceOf(row)?.let { return it }
        Regex("(\\d{1,4})\\s(\\d{2})\\s*$").find(row)?.let { m ->
            if (m.range.first > 0 && row[m.range.first - 1].isDigit()) return null
            return "${m.groupValues[1]}.${m.groupValues[2]}".toDouble() to m.range.first
        }
        Regex("(?:^|\\s)(\\.?00)\\s*$").find(row)?.let { m -> return 0.0 to m.range.first }
        return null
    }

    /** Card / e-wallet payment rows (not the word "card" alone, which items can contain). */
    private val paymentEnd = Regex(
        "\\b(master\\s*card|mastercard|visa|debit|credit|duitnow|tng|touch\\s*n\\s*go|grabpay|boost|shopeepay|e-?wallet)\\b|[X*]{4,}",
        RegexOption.IGNORE_CASE
    )

    /** First row of the totals area: total, subtotal, charges, rounding or payment. */
    private fun isTotalsRow(row: String): Boolean =
        // Any "total" line ends the items, including "Total products / items / qty".
        totalWords.containsMatchIn(row) ||
                subtotalWords.containsMatchIn(row) ||
                roundingWords.containsMatchIn(row) ||
                paymentEnd.containsMatchIn(row) ||
                (chargeWords.containsMatchIn(row) && trailingPriceOf(row) != null && !modifierStart.containsMatchIn(row))

    /** (price, index where it starts) for a price at the end of the row, after OCR clean-up. */
    private fun trailingPriceOf(row: String): Pair<Double, Int>? {
        val m = trailingPrice.find(row) ?: return null
        val raw = m.groupValues[1].replace(" ", "")
        val fixed = parser.normalizePriceLine(raw.removePrefix("-")).let { if (raw.startsWith("-")) "-$it" else it }
        var value = fixed.replace(Regex("^-\\."), "-0.").replace(Regex("^\\."), "0.").toDoubleOrNull() ?: return null
        if (abs(value) > 9999.0) return null
        // Minus written after the price ("2.94-") or before "RM" ("-RM2.94").
        val before = row.substring(0, m.range.first).trimEnd()
        if (value > 0.0 && (m.groupValues[2] == "-" || Regex("-\\s*R\\s?[MH]$", RegexOption.IGNORE_CASE).containsMatchIn(before))) {
            value = -value
        }
        // "-RM2.94": the label ends before the "-RM", not at the number.
        val start = Regex("-?\\s*R\\s?[MH]$", RegexOption.IGNORE_CASE).find(before)?.range?.first ?: m.range.first
        return value to minOf(start, m.range.first)
    }

    /** "1X MEN POLO" / "MEN POLO 2" / "2 x Latte" → (qty, name part). */
    private fun splitQuantity(text: String): Pair<Int, String> {
        var t = text.trim()
        var qty = 1
        Regex("^(\\d{1,2})\\s*[xX]?\\s+(.*)$").find(t)?.let { m ->
            qty = m.groupValues[1].toInt(); t = m.groupValues[2]
        } ?: Regex("^(.*?)\\s+[xX]?(\\d{1,2})$").find(t)?.let { m ->
            qty = m.groupValues[2].toInt(); t = m.groupValues[1]
        }
        return qty.coerceIn(1, 99) to t
    }

    private fun cleanName(raw: String): String {
        var n = raw.trim()
        n = n.replace(Regex("^\\d{1,3}[).]\\s*"), "")           // "1) "
        n = n.replace(Regex("^[(\\[]\\s*[A-Za-z]{0,2}\\d{1,3}\\s*[)\\]]\\s*"), "") // "(B8) ", "[B3] "
        // Header text bled into the row: "(Reg No. 200001018812)". Before the code
        // removal below, which would leave "(Reg No." behind.
        n = n.replace(Regex("\\(?\\s*Reg\\.?\\s*[NH]o\\.?\\s*:?\\s*\\d*\\)?", RegexOption.IGNORE_CASE), "")
        n = n.replace(Regex("\\b[A-Z]{0,3}\\d{6,}\\b"), "")       // item codes like IN20529599002
        n = n.replace(Regex("\\(\\s*\\d+(\\.\\d{2})?\\s*/\\s*ea\\s*\\)", RegexOption.IGNORE_CASE), "") // "(6.90/ea)"
        n = n.replace(Regex("\\d+\\s*@\\s*\\d*\\.\\d{2}(\\s*/\\s*\\w+)?"), "")      // "8@ 6.49" merged in
        n = n.replace(Regex("\\d+(\\.\\d{2})?\\s*-\\s*\\(.*$"), "") // "39.90-( 4.90)C = 35.00"
        n = n.replace(Regex("[=@]+.*$"), "")
        n = n.replace(Regex("\\s{2,}"), " ")
        return n.trim().trim('-', ':', '.', ',')
    }

    private fun chargeType(lower: String): String = when {
        lower.contains("service charge") -> "Service Charge"
        lower.contains("service tax") -> "Service Tax"
        lower.contains("sst") -> "SST"
        lower.contains("gst") -> "GST"
        else -> "Other"
    }

    private fun rateOf(row: String): Double? =
        Regex("(\\d{1,2}(?:\\.\\d)?)\\s*%").find(row)?.groupValues?.get(1)?.toDoubleOrNull()
}
