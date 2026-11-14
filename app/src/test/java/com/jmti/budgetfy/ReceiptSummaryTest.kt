package com.jmti.budgetfy

import com.google.firebase.Timestamp
import com.jmti.budgetfy.models.AddOn
import com.jmti.budgetfy.models.Receipt
import com.jmti.budgetfy.models.ReceiptCharge
import com.jmti.budgetfy.models.ReceiptItem
import com.jmti.budgetfy.utils.ReceiptSummary
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date

class ReceiptSummaryTest {

    @Test
    fun keysAreInTheAgreedOrder() {
        val receipt = Receipt(
            userId = "u1",
            storeName = "Thai Cuisine",
            date = Timestamp(Date(1_763_000_000_000L)),
            createdAt = 1_763_000_000_000L,
            items = listOf(
                ReceiptItem(name = "C5 Tom Yam", price = 21.90, addOns = listOf(AddOn("Egg Noodle", 2.0))),
                ReceiptItem(name = "Iced Tea", quantity = "2", price = 6.0)
            ),
            charges = listOf(ReceiptCharge(type = "SST", rate = 6.0, amount = 1.67)),
            totalAmount = 29.57
        )
        val s = ReceiptSummary.build(receipt, "Kotohaaa", "a@b.c")

        assertEquals(
            listOf(
                "01 Display name", "02 User ID", "03 Date", "04 Email", "05 Created at",
                "06 Store", "07 Item 1", "08 Item 2", "09 Tax", "10 Total paid"
            ),
            s.keys.toList()
        )
        // The console sorts keys alphabetically; zero-padding keeps the same order.
        assertEquals(s.keys.toList(), s.keys.sorted())
        assertEquals("Thai Cuisine", s["06 Store"])
        assertEquals("C5 Tom Yam ×1 RM 21.90 (+ Egg Noodle RM 2.00)", s["07 Item 1"])
        assertEquals("SST 6% RM 1.67", s["09 Tax"])
        assertEquals("RM 29.57", s["10 Total paid"])
    }
}
