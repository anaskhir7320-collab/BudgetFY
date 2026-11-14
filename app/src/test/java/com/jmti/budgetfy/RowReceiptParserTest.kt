package com.jmti.budgetfy

import com.jmti.budgetfy.utils.RowReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row-based offline reading, using OCR rows from real receipts. */
class RowReceiptParserTest {

    private val parser = RowReceiptParser()

    // Thong Empire noodle shop, OCR rows as read on the phone (12 Nov 2026).
    private val noodle = """
        Thong EMPIRE Sdn Bhd
        202301048621 (1542535-T)
        Tax ID:W10-250-32000021
        1F-K01 Sunway Carnival Nall 3068, Jalan Todak
        Pusat Bandar Seberang Jaya, Seberang Jaya,
        13700 Peral, Penang
        TAX INVOICE
        Dine In
        Table: 18
        Bill No: 9001075111   COMPLETED
        Date: 12/11/2026   16:27:30
        16:27:29
        Item Name   Qty   Price(RM)
        C1 Shredded   19.90
        Chicken & Chicken
        Ball Noodle (Soup)
        :Egg Noodle   0.00
        :Level 1   1   0.00
        :All Topping on   1   0.00
        side
        C1 Shredded   1   19.90
        Chicken & Chicken
        Ball Noodle (Soup)
        :Thai Flat   0.00
        Noodle "Chef's
        Recommendati
        on"
        :Level 2   1   0.00
        :All Topping on   0.00
        side
        Total Anount   46.15
        Total Discount   00
        Sub Total   46.16
        Service Charge 10%   3.98
        Tax Arnount   2.38
        Rounding   -.01
        46.15
    """.trimIndent()

    // Padini Brands Outlet, OCR rows as read on the phone (10 Nov 2026).
    private val padini = """
        BRANDS 0UTLET
        DESIGN VILLAGE PENANG
        Yee Fong Ihng (M) Sdn Hhl (15011-0)
        NO.19, JALAN JURUNILAT U1/20,
        HICOM GLENMARIE INDUSTRTAL PARK,
        40150, SHAH ALAM, SELANGOR
        Tel:04-589 9052
        www.padini. com
        10/11/2026-14:02:03-69-CHENG AI JYN-1135-
        03
        INVOICE NO. : 3108951
        DATE PRINTED: 10/11/2026 02:02:03 PM
        1) IN20529599002   STONE M
        MEN POLO BS/RG/MD
        1X 39.90-(   4.90)C = 35.00
        2) IN20531379005   NAVY XXL
        MEN POLD BS/RG
        1X   39.90- (   4.90)C = 35.00
        2   9.80   70.00
        Rounding Acdjustent   0.00
        Rounding   70.00
        XXXXXXXXXXXX2554   MASTER CARD   70.00
    """.trimIndent()

    // ZUS Coffee, as printed (tax included in prices).
    private val zus = """
        Qty   Item   Price (MYR)
        1   Iced CEO Americano  (6.90/ea)   7.50
        • +Brwnie Crmb + 0.60
        1   Iced Shaken Double Espresso   11.20
        (11.20/ea)
        1   BB Cafe Latte  (21.90/ea)   21.90
        3   Qty
        Subtotal   40.60
        SST (Inclu. 6%)   2.30
        Bill rounding   0.00
        Total (MYR)   40.60
        TERMINAL_MAYBANK   40.60
    """.trimIndent()

    @Test
    fun noodleShop() {
        val r = parser.parse(noodle)
        assertEquals(2, r.items.size)
        assertEquals(19.90, r.items[0].price, 0.001)
        assertEquals(19.90, r.items[1].price, 0.001)
        assertTrue(r.items[0].name, r.items[0].name.startsWith("C1 Shredded Chicken"))
        assertEquals(46.15, r.total!!, 0.001)
        // 39.80 items + 3.98 service charge + 2.38 tax - 0.01 rounding = 46.15
        assertEquals(46.15, r.items.sumOf { it.price } + r.extras, 0.001)
        assertTrue(r.charges.any { it.type == "Service Charge" && it.rate == 10.0 && !it.inclusive })
    }

    @Test
    fun noodleShopTiltedTotals() {
        // Totals exactly as OCR read them from a slightly tilted photo
        // (13 Nov 2026): every number sits one row below its label, with
        // "3 98" and "46. 15" spacing.
        val tilted = """
            Item Name   Qty   Price(RM)
            C1 Shredded   1   19.90
            Chicken & Chicken
            Ball Noodle (Soup)
            :Egg Noodle   1   0.00
            :All Topping on   1   0.00
            side
            C1 Shredded   1   19.90
            Chicken & Chicken
            Ball Noodle (Soup)
            :Thai Flat   0.00
            :Al| Topping on
            side
            Total Arnount   .00
            Total Discount   46.16
            Sub Total   3 98
            Service Charge 10%   2.38
            Tax Arnount   -.01
            Rounding
            46. 15
            Tax ID:W10-2506-32000021
            TAX INVOICE
        """.trimIndent()
        val r = parser.parse(tilted)
        assertEquals(2, r.items.size)
        assertEquals(46.15, r.total!!, 0.001)
        val service = r.charges.single { it.type == "Service Charge" }
        assertEquals(3.98, service.amount, 0.001)
        assertEquals(10.0, service.rate!!, 0.001)
        val tax = r.charges.single { it.type != "Service Charge" }
        assertEquals(2.38, tax.amount, 0.001)
        assertEquals(46.15, r.items.sumOf { it.price } + r.extras, 0.001)
    }

    @Test
    fun mixue() {
        // OCR rows as read on the phone (13 Nov 2026), misspellings included.
        val mixue = """
            MIXUE
            5/NCE 199/-ICE CREA M &TEA
            DENUSAN FOOD & BEVERAGE
            SSM number: 202403185322 (RAO114593-T)
            Local[Dine-in]
            #103
            MIXUE ( otus Bukit Mer täjam)
            Order number: 261 1 12191710203
            Order time: 2026 11- 12 19:16:47
            Name   inil price Quàntily Sub-iotal
            (B8) Fresh Lemonade (Vest)
            3.000   3.00
            Feature: Normnal Ice,Normal Suqar
            (B3] Mango Jasniine Te.i (West)(Cold-Chain)
            6.00   1   6.00
            Feature: Normal Ice,Nommal Sujal
            Total products
            2   9.00
            Payment detail
        """.trimIndent()
        val r = parser.parse(mixue)
        assertEquals(2, r.items.size)
        assertEquals("Fresh Lemonade (Vest)", r.items[0].name)
        assertEquals(3.00, r.items[0].price, 0.001)
        assertTrue(r.items[1].name, r.items[1].name.startsWith("Mango"))
        assertEquals(6.00, r.items[1].price, 0.001)
        assertEquals(9.00, r.total!!, 0.001)
    }

    @Test
    fun lotusBuy3Discount() {
        // Lotus's Bukit Mertajam, 13 Nov 2026 (as printed on the receipt).
        val lotus = """
            LOTUSS STORES (MALAYSIA) SDN.BHD
            2177300051402  LOTUSS WHOLE CHICKEN W H&   51.40
            7.460 kg @ 6.89/kg
            2187380051926  WHOLE SOTONG RENDAM   51.92
            8 @ 6.49
            2124050008576  THAI LONGAN WITH STEM - K   8.57
            1.431 kg @ 5.99/kg
            2124050005070  THAI LONGAN WITH STEM - K   5.07
            0.846 kg @ 5.99/kg
            9551021625287  ZUS CEO CHOCOLATE IN   29.98
            2 @ 14.99
            9551003340002  GS COOKING OIL1KG PA   5.00
            2 @ 2.50
            BUY 3 FOR RM 18   -RM2.94
            TOTAL   149.00
            Total Sold Items:   15
            On-line card   149.00
        """.trimIndent()
        val r = parser.parse(lotus)
        assertEquals(6, r.items.size)
        assertEquals("LOTUSS WHOLE CHICKEN W H&", r.items[0].name) // weight line not glued on
        assertEquals("ZUS CEO CHOCOLATE IN", r.items[4].name)
        assertEquals(151.94, r.items.sumOf { it.price }, 0.001)
        val discount = r.charges.single { it.type == "Discount" }
        assertEquals(-2.94, discount.amount, 0.001)
        assertEquals(149.00, r.total!!, 0.001)
        assertEquals(149.00, r.items.sumOf { it.price } + r.extras, 0.001)
    }

    @Test
    fun lotusHandHeldOfflineScan() {
        // OCR rows from the offline scan (13 Nov 2026): neighbouring lines merged.
        val scan = """
            Lotus's
            BUKIT MERTAJAM
            LOTUSS STORES (HALAYS IA) SDN. BHD   (521419-K))
            2177300051402 LOTUSS WHOLE CHTCKEN V HE   (Reg Ho. 200001018812   51.40
            7.460 kg @ 6.89/k9
            2187380051926 WHOLE SOTONG RENDAM   51.92
            2124050008576 THAI LÖNGAN WITH STEM K   8@ 6.49   8.57
            1.431 kg @ 5.99/kg   2124050005070 THAI LONGAN WITH STEM -K   5.07
            0.846 kg @ 5.99/kg   9551021626287 ŽUS CEO CHOCOLATE IN   29.98
            9551003340002 GS COOKING 0IL 1KG PA   2@ 14.99
            5.00
            2@ 2.50   -RM2.94
            BUY 3 FOR RM 18
            TOTAL   149.00   15
            Total Sold Itens:
            On-line card   149.00
            MYDEBIT
            519603x******8066
            APPROVAL CODE:050784
            TOTAL:   RM 149.00
        """.trimIndent()
        val r = parser.parse(scan)
        assertEquals(listOf(51.40, 51.92, 8.57, 5.07, 29.98, 5.00), r.items.map { it.price })
        assertEquals("LOTUSS WHOLE CHTCKEN V HE", r.items[0].name)
        assertEquals("THAI LÖNGAN WITH STEM K", r.items[2].name)
        assertEquals("GS COOKING 0IL 1KG PA", r.items[5].name)
        // "8@ 6.49" sits on the longan row but 8 x 6.49 = 51.92 is the sotong.
        assertEquals(listOf("1", "8", "1", "1", "2", "2"), r.items.map { it.quantity })
        assertEquals(-2.94, r.charges.single { it.type == "Discount" }.amount, 0.001)
        assertEquals(149.00, r.total!!, 0.001)
    }

    @Test
    fun noodleAddOnsStayUnderTheirItem() {
        val r = parser.parse(noodle)
        assertEquals(2, r.items.size)
        assertEquals("C1 Shredded Chicken & Chicken Ball Noodle (Soup)", r.items[0].name)
        assertEquals(listOf("Egg Noodle", "Level 1", "All Topping on side"), r.items[0].addOns.map { it.name })
        assertEquals(
            listOf("Thai Flat Noodle \"Chef's Recommendation\"", "Level 2", "All Topping on side"),
            r.items[1].addOns.map { it.name }
        )
        assertEquals(19.90, r.items[0].price, 0.001) // free add-ons don't change the price
    }

    @Test
    fun pricedAddOnIsPartOfItemNotSeparate() {
        val receipt = """
            Item Name   Qty   Price(RM)
            Chicken Rice   1   10.00
            :Egg Noodle   1   2.00
            :Less Spicy   1   0.00
            Iced Tea   1   3.50
            Total   15.50
        """.trimIndent()
        val r = parser.parse(receipt)
        assertEquals(2, r.items.size) // Egg Noodle is not its own item
        assertEquals(12.00, r.items[0].price, 0.001) // 10.00 + 2.00 add-on (total says so)
        assertEquals(2.00, r.items[0].addOns.first { it.name == "Egg Noodle" }.price, 0.001)
        assertEquals(15.50, r.total!!, 0.001)
    }

    @Test
    fun zusAddOnAlreadyInLinePrice() {
        val r = parser.parse(zus)
        assertEquals("Brwnie Crmb", r.items[0].addOns.single().name)
        assertEquals(7.50, r.items[0].price, 0.001) // not 8.10: 0.60 is already in 7.50
    }

    // Thai Cuisine receipt, OCR rows as read on the phone (14 Nov 2026).
    private val thai = """
        Item Name   Qty   Price(RM)
        C5 Creamy Tom   19.90
        Yum Marna Mee
        with Squid, Prawn,
        Chicken ball and
        Mushroom
        :AO5 Egg   1   2.00
        Noodle
        G3 Mega Signature   1   29.90
        Milk Tea
        CP03 Crispy   32.90
        Chicken Noodle
        Soup (15mins)
        Egg Noodle   1   0.00
        :Spicy   1   0.00
        Total Amount   98.25
        Total Discount   .00
        Sub Total   98.24
        Service Charge 10%   8.47
        Tax Amnount   5.07
        Rounding   .01
        98.25
    """.trimIndent()

    @Test
    fun thaiPricedAddOnOffline() {
        val r = parser.parse(thai)
        assertEquals(3, r.items.size) // ":AO5 Egg Noodle 2.00" is not its own item
        assertEquals(21.90, r.items[0].price, 0.001) // 19.90 + 2.00
        assertEquals("Egg Noodle", r.items[0].addOns.single().name)
        assertEquals(listOf("Egg Noodle", "Spicy"), r.items[2].addOns.map { it.name })
        assertEquals(98.25, r.total!!, 0.001)
    }

    @Test
    fun geminiPricedAddOnMovedUnderItem() {
        // What Gemini returned: the marked add-on as a 4th item.
        val gemini = listOf(
            com.jmti.budgetfy.models.ReceiptItem(name = "C5 Creamy Tom Yum Mama Mee with Squid, Prawn, Chicken ball and Mushroom", price = 19.90),
            com.jmti.budgetfy.models.ReceiptItem(name = "AO5 Egg Noodle", price = 2.00),
            com.jmti.budgetfy.models.ReceiptItem(name = "G3 Mega Signature Milk Tea", price = 29.90),
            com.jmti.budgetfy.models.ReceiptItem(name = "CP03 Crispy Chicken Noodle Soup (15mins)", price = 32.90)
        )
        val fixed = com.jmti.budgetfy.utils.AddOnMerger.attachMarkedAddOns(gemini, thai)
        assertEquals(3, fixed.size)
        assertEquals(21.90, fixed[0].price, 0.001)
        assertEquals("Egg Noodle", fixed[0].addOns.single().name)
        assertEquals(2.00, fixed[0].addOns.single().price, 0.001)
        assertEquals(84.70, fixed.sumOf { it.price }, 0.001) // total unchanged
    }

    @Test
    fun padiniBrandsOutlet() {
        val r = parser.parse(padini)
        assertEquals(2, r.items.size)
        assertEquals("MEN POLO BS/RG/MD", r.items[0].name)
        assertEquals(35.00, r.items[0].price, 0.001)
        assertEquals(35.00, r.items[1].price, 0.001)
        assertEquals(70.00, r.total!!, 0.001) // no "Total" line: taken from the card payment
    }

    @Test
    fun zusCoffee() {
        val r = parser.parse(zus)
        assertEquals(3, r.items.size)
        assertEquals("Iced CEO Americano", r.items[0].name)
        assertEquals(7.50, r.items[0].price, 0.001) // add-on already inside the line price
        assertEquals(40.60, r.items.sumOf { it.price }, 0.001)
        assertEquals(40.60, r.total!!, 0.001)
        assertTrue(r.charges.single().inclusive) // SST included, not added
    }
}
