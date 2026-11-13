package com.jmti.budgetfy

import com.jmti.budgetfy.models.ReceiptItem
import com.jmti.budgetfy.utils.OfflineReceiptReader
import com.jmti.budgetfy.utils.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Offline reading: price clean-up and total-based repair. */
class OfflineReadingTest {

    private val parser = ReceiptParser()

    @Test
    fun fixesOcrMixUpsInPriceLines() {
        assertEquals("7.90", parser.normalizePriceLine("7.9O"))
        assertEquals("7.90", parser.normalizePriceLine("7,90"))
        assertEquals("15.50", parser.normalizePriceLine("1S.50"))
        assertEquals("7.90", parser.normalizePriceLine("7. 90"))
        assertEquals("11.20", parser.normalizePriceLine("1l.20"))
        assertEquals("RM 21.90", parser.normalizePriceLine("RM21,90"))
    }

    @Test
    fun dropsTaxCodeAfterPrice() {
        assertEquals("7.90", parser.normalizePriceLine("7.90 SR"))
        assertEquals("3.50", parser.normalizePriceLine("3.50 Z"))
    }

    @Test
    fun leavesOtherLinesAlone() {
        assertEquals("Iced CEO Americano", parser.normalizePriceLine("Iced CEO Americano"))
        assertEquals("SOS.IO", parser.normalizePriceLine("SOS.IO")) // no real digit: not a price
        assertEquals("Lot 12, Jalan Besar", parser.normalizePriceLine("Lot 12, Jalan Besar"))
        assertEquals("12:30", parser.normalizePriceLine("12:30"))
    }

    @Test
    fun repairsOneMisreadPriceUsingTheTotal() {
        val result = ReceiptParser.ParseResult(
            items = listOf(item("Latte", 8.90), item("Espresso", 11.20), item("Brownie", 0.60)),
            receiptTotal = 18.70, totalFound = true
        )
        val fixed = OfflineReceiptReader.repairToTotal(result)
        assertEquals(6.90, fixed[0].price, 0.001) // 8.90 -> 6.90 (one digit)
        assertEquals(11.20, fixed[1].price, 0.001)
    }

    @Test
    fun leavesItemsWhenRepairIsNotCertain() {
        // Off by RM 5.00: no single one-digit change explains it.
        val result = ReceiptParser.ParseResult(
            items = listOf(item("A", 1.00), item("B", 2.00)),
            receiptTotal = 8.00, totalFound = true
        )
        val fixed = OfflineReceiptReader.repairToTotal(result)
        assertEquals(1.00, fixed[0].price, 0.001)
        assertEquals(2.00, fixed[1].price, 0.001)
    }

    @Test
    fun keepsTheReadingThatAddsUp() {
        val ocr = com.jmti.budgetfy.utils.OCRHelper.OcrOutput("", "")
        val wrong = OfflineReceiptReader.Reading(ocr, ReceiptParser.ParseResult(
            listOf(item("A", 5.00), item("B", 9.00)), 12.00, true))
        val right = OfflineReceiptReader.Reading(ocr, ReceiptParser.ParseResult(
            listOf(item("A", 5.00), item("B", 7.00)), 12.00, true))
        assertEquals(right, OfflineReceiptReader.pickBest(listOf(wrong, right)))
    }

    @Test
    fun dishesAreFoodNotGroceries() {
        val engine = com.jmti.budgetfy.utils.CategorizationEngine()
        assertEquals("Food & Dining", engine.categorizeItem("C1 Shredded Chicken & Chicken Ball Noodle (Soup)"))
        assertEquals("Food & Dining", engine.categorizeItem("Nasi Goreng Ayam"))
        assertEquals("Groceries", engine.categorizeItem("Fresh Chicken 1kg"))
    }

    @Test
    fun defaultStoreNameIsNeverRenamed() {
        val engine = com.jmti.budgetfy.utils.CategorizationEngine()
        val learned = listOf(com.jmti.budgetfy.models.LearnedKeyword(original = "__store__:receipt", correctedName = "Molly"))
        assertEquals("Receipt", engine.applyLearnedStoreName("Receipt", learned))
    }

    private fun item(name: String, price: Double) = ReceiptItem(name = name, price = price)
}
