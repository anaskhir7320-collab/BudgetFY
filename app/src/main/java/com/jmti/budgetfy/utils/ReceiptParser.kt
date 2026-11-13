package com.jmti.budgetfy.utils

import android.util.Log
import com.jmti.budgetfy.models.ReceiptItem
import java.util.regex.Pattern

class ReceiptParser {

    private val categorizationEngine = CategorizationEngine()

    private val purePricePattern = Pattern.compile("^(\\d{1,4}\\.\\d{2})$")
    private val pureIntPattern = Pattern.compile("^(\\d{1,3})$")
    private val rmPricePattern = Pattern.compile("R[MHJN]\\s*[J1l]?\\s*(\\d{1,4}\\.\\d{2})")
    private val anyPricePattern = Pattern.compile("(\\d{1,4}\\.\\d{2})")

    // ============================================================
    //  METADATA KEYWORDS — if line contains ANY of these → NOT an item
    // ============================================================
    private val metadataKeywords = listOf(
        "receipt", "invoice", "order", "sales invoice", "tax invoice",
        "date", "time", "batch",
        "payment", "cash", "change", "credit", "debit", "card",
        "mydebit", "duitnow", "tng", "touch n go",
        "total", "subtotal", "sub-total", "grand", "sub total", "rounding",
        "tax", "gst", "sst", "service", "bill inclusive", "bill include",
        "no.", "no:", "no :", "id:", "ref", "ref-1", "biller", "code",
        "batch num", "terminal", "merchant", "approval", "appr",
        "item name", "description", "desc", "qty", "quantity", "price",
        "unit price", "amt", "amount",
        "sdn bhd", "sdn. bhd", "sdn", "bhd", "s/b", "enterprise",
        "cafe", "restaurant", "restaurants", "restoran",
        "lot ", "taman", "jalan", "lorong", "persiaran",
        "seberang", "pulau", "bukit", "klang", "selangor", "penang", "perai",
        "kuala lumpur", "shah alam", "shah alan",
        "thank", "welcome", "terima kasih", "please come again", "keep the invoice",
        "url", "http", "www", "visit", "e-invoice", "einvoice",
        "feature", "normal ice", "ice,", "sugar", "pos:", "staff", "cashier",
        "take away", "takeaway", "dine-in", "dine in", "local[",
        "ssm", "signature", "receipt no", "receipt date", "receipt amount",
        "menu", "rahmah", "drinking water", "drink water",
        "sd", "vice", "v1ce", "vlce", "v1c3",
        "irasaction", "iranaaction", "iransaction", "tranaaction", "tlanaaction",
        "information", "lnformation", "lntomation", "nlomation", "ntomation",
        "intonation", "intomhation", "tine", "lime",
        "restauant", "restautant", "restaufant", "restaunant", "restaurant no",
        "recelpt", "receipi", "receip", "recetpi", "reecelpt", "rechipi",
        "paysnent", "paynent", "ipayment", "ipaynment", "ipaynent",
        "paynient",
        "cand", "fxp", "appr",
        "ilerminal", "ierminal", "llerminal", "iterninal",
        "csedit", "credit /", "credit/",
        "grarnd", "grand", "gand", "gtand",
        "pate &", "pate ",
        "cashless", "cashles5",
        "e-inyoice", "recsipt",
        "ast (rm)", "aati", "aat ", "aat(",
        "mydebit", "kyüebit", "kyuebit",
        "debit (",
        "apply", "appl aid", "app crypt", "tvr", "tur value",
        "ent type", "host", "mid", "tid", "type", "sale"
    )

    // ============================================================
    //  PRODUCT KEYWORDS — real products contain one of these
    // ============================================================
    private val productKeywords = listOf(
        "rice", "bowl", "noodle", "pasta", "bread", "cake", "muffin",
        "cookie", "pizza", "burger", "sandwich", "chicken", "beef", "fish",
        "soup", "salad", "fries", "taco", "wrap", "curry",
        "lemonade", "tea", "coffee", "juice", "water", "drink", "soda",
        "cola", "milk", "matcha", "latte", "mocha", "smoothie",
        "chocolate", "egg", "sugar", "salt", "flour", "oil", "sauce",
        "cereal", "yogurt", "cheese", "butter", "fresh", "farm",
        "shampoo", "soap", "toothpaste", "lotion", "cream", "sunscreen",
        "medicine", "pill", "vitamin", "syrup",
        "pack", "bottle", "can", "box", "cup", "slice", "piece", "set",
        "ball", "menu set"
    )

    fun parseReceiptText(text: String, avoidPatterns: List<String> = emptyList()): List<ReceiptItem> =
        parseReceipt(text, avoidPatterns).items

    fun parseReceipt(text: String, avoidPatterns: List<String> = emptyList()): ParseResult {
        val lines = text.lines().map { normalizePriceLine(it.trim()) }.filter { it.isNotEmpty() }
        Log.d("PARSER", "==== Total lines: ${lines.size} ====")

        // ── Step 1: Extract receipt total ──
        var receiptTotal = 0.0
        var totalLineIndex = -1
        for ((i, l) in lines.withIndex()) {
            if (isTotalLine(l.lowercase())) {
                totalLineIndex = i
                for (j in i until minOf(i + 8, lines.size)) {
                    val prices = extractAllPrices(lines[j])
                    for (p in prices) if (p > receiptTotal && p < 999.99) receiptTotal = p
                }
                break
            }
        }
        Log.d("PARSER", "Total line at index $totalLineIndex")
        Log.d("PARSER", "Receipt total: $receiptTotal")
        val totalFound = receiptTotal > 0.0

        // ── Step 2: Identify item candidates ──
        val nameIndices = mutableListOf<Int>()
        val scanEnd = if (totalLineIndex >= 0) totalLineIndex else lines.size
        for (i in 0 until scanEnd) {
            val line = lines[i]
            if (isStopLine(line.lowercase())) continue
            if (!looksLikeName(line)) continue
            if (!isQualityProductName(line)) continue
            if (line.matches(Regex(".*\\d{1,4}\\.\\d{2}.*"))) continue
            if (line.matches(Regex("^\\d+$"))) continue
            nameIndices.add(i)
        }
        Log.d("PARSER", "Name candidates: $nameIndices")

        val items = mutableListOf<ReceiptItem>()
        val consumedPrices = mutableSetOf<Int>()

        for ((k, nameIdx) in nameIndices.withIndex()) {
            val name = cleanName(lines[nameIdx])
            if (name.length < 3) continue
            if (!name.matches(Regex(".*[A-Za-z]{2,}.*"))) continue
            if (items.any { it.name.equals(name, ignoreCase = true) }) continue

            // ✅ Skip if matches a learned avoid pattern
            if (shouldAvoid(name, avoidPatterns)) {
                Log.d("PARSER", "Avoided: $name")
                continue
            }

            val nextNameIdx = if (k + 1 < nameIndices.size) {
                nameIndices[k + 1]
            } else {
                if (totalLineIndex >= 0) minOf(totalLineIndex + 8, lines.size) else lines.size
            }

            val pricesInRange = mutableListOf<Double>()
            val qtyInRange = mutableListOf<Int>()

            for (m in (nameIdx + 1) until nextNameIdx) {
                if (m in consumedPrices) continue
                val l = lines[m]

                if (m < totalLineIndex && isStopLine(l.lowercase())) continue
                if (m >= totalLineIndex) {
                    val isPure = l.matches(Regex("^\\d+(\\.\\d{2})?$"))
                    if (!isPure) continue
                }

                if (purePricePattern.matcher(l).matches()) {
                    l.toDoubleOrNull()?.let {
                        if (it in 0.10..999.99) {
                            pricesInRange.add(it)
                            consumedPrices.add(m)
                        }
                    }
                    continue
                }
                if (pureIntPattern.matcher(l).matches()) {
                    l.toIntOrNull()?.let { if (it in 1..99) qtyInRange.add(it) }
                }
            }

            Log.d("PARSER", "[$nameIdx] '$name' prices=$pricesInRange qty=$qtyInRange")

            if (pricesInRange.isNotEmpty()) {
                val price = pricesInRange.max()
                val quantity = pickQuantity(pricesInRange, qtyInRange)
                val category = categorizationEngine.categorizeItem(name)
                items.add(
                    ReceiptItem(
                        name = name,
                        quantity = quantity,
                        price = price,
                        category = category
                    )
                )
                Log.d("PARSER", "Item: $name | x$quantity | RM $price")
            } else {
                val found = findPriceBelow(nameIdx, lines, totalLineIndex, consumedPrices)
                if (found != null) {
                    val cat = categorizationEngine.categorizeItem(name)
                    items.add(
                        ReceiptItem(
                            name = name,
                            quantity = "1",
                            price = found.second,
                            category = cat
                        )
                    )
                    consumedPrices.add(found.first)
                    Log.d("PARSER", "Deferred: $name | RM ${found.second}")
                }
            }
        }

        // ── Fallback pass — only if first pass empty ──
        if (items.isEmpty()) {
            Log.d("PARSER", "Fallback: no items found.")

            val allNameIndices = mutableListOf<Int>()
            for (i in lines.indices) {
                val line = lines[i]
                val lower = line.lowercase()
                if (isStopLine(lower)) continue
                if (lower.contains("receipt")) continue
                if (lower.contains("invoice")) continue
                if (lower.contains("cash")) continue
                if (lower.contains("change")) continue
                if (lower.contains("total sales")) continue
                if (lower.contains("total ")) continue
                if (!looksLikeName(line)) continue
                if (!isQualityProductName(line)) continue
                if (line.matches(Regex(".*\\d{1,4}\\.\\d{2}.*"))) continue
                if (line.matches(Regex("^\\d+$"))) continue
                if (lower.trim() == "rm") continue
                allNameIndices.add(i)
            }
            Log.d("PARSER", "Fallback candidates: $allNameIndices")

            for (nameIdx in allNameIndices) {
                val name = cleanName(lines[nameIdx])
                if (name.length < 3) continue
                if (!name.matches(Regex(".*[A-Za-z]{2,}.*"))) continue
                if (name.lowercase().trim() == "rm") continue
                if (items.any { it.name.equals(name, ignoreCase = true) }) continue

                // ✅ Skip if matches a learned avoid pattern
                if (shouldAvoid(name, avoidPatterns)) {
                    Log.d("PARSER", "Avoided (fallback): $name")
                    continue
                }

                val found = findPriceBelow(nameIdx, lines, -1, consumedPrices)
                if (found != null) {
                    val cat = categorizationEngine.categorizeItem(name)
                    items.add(
                        ReceiptItem(
                            name = name,
                            quantity = "1",
                            price = found.second,
                            category = cat
                        )
                    )
                    consumedPrices.add(found.first)
                    Log.d("PARSER", "Fallback item: $name | RM ${found.second}")
                }
            }

            if (receiptTotal == 0.0 && items.isNotEmpty()) {
                receiptTotal = items.sumOf { it.price }
            }
        }

        if (receiptTotal == 0.0 && items.isNotEmpty()) receiptTotal = items.sumOf { it.price }

        Log.d("PARSER", "FINAL: ${items.size} items, total=RM $receiptTotal")
        return ParseResult(items = items, receiptTotal = receiptTotal, totalFound = totalFound)
    }

    // ============================================================
    //  ✅ NEW: Check if a line matches any avoid pattern
    // ============================================================
    private fun shouldAvoid(line: String, avoidPatterns: List<String>): Boolean {
        if (avoidPatterns.isEmpty()) return false
        val lower = line.lowercase().trim()
        return avoidPatterns.any { pattern ->
            lower == pattern ||
                    lower.startsWith("$pattern ") ||
                    lower.endsWith(" $pattern")
        }
    }

    private fun isQualityProductName(line: String): Boolean {
        val t = line.trim()
        if (t.length < 4) return false
        if (t.contains(":")) return false
        if (t.contains("|")) return false

        val words = t.split("\\s+".toRegex()).filter { it.isNotEmpty() }
        if (words.size == 1 && t == t.uppercase()) return false

        if (t.contains(" No ")) return false
        if (t.contains(" No:")) return false
        if (t.matches(Regex(".*\\sNo\\s.*"))) return false
        if (t.endsWith(" No")) return false

        val metadataPrefixes = listOf(
            "Receipt", "Invoice", "Order", "Date", "Time", "Cashier",
            "Payment", "Card", "Batch", "Terminal", "Merchant", "Bill",
            "Restaurant", "Restautant", "Restauant", "Restaufant",
            "Grand", "Sub", "Total", "Rounding", "Change", "Cash",
            "Credit", "Debit", "Tax", "Service", "Take", "Item",
            "YOUR", "Y O U R", "Price", "Qty", "Description", "Desc",
            "Paynent", "Cand", "Fxp", "Exp", "Appr", "Ierminal",
            "Tlanaaction", "Tranaaction", "Grarnd", "Pate", "Cad",
            "IPaynment", "IPaynent", "Ilerminal", "Naration",
            "Paysnent", "Bil ", "Ser ", "Restaunant", "Rechipi",
            "Unit pice", "Zuantity", "Featute", "Nctal", "Nounal",
            "Noal", "Oider", "Total praduct"
        )
        for (prefix in metadataPrefixes) {
            if (t.startsWith(prefix, ignoreCase = true)) return false
        }

        if (words.size == 1 && t.length <= 8 && t == t.uppercase()) return false
        if (t.matches(Regex("^[A-Z]{2,}$"))) return false

        return true
    }

    private fun findPriceBelow(
        nameIdx: Int,
        lines: List<String>,
        totalLineIndex: Int,
        consumed: Set<Int>
    ): Pair<Int, Double>? {
        val end = if (totalLineIndex >= 0) minOf(totalLineIndex + 8, lines.size) else lines.size
        for (i in (nameIdx + 1) until end) {
            if (i in consumed) continue
            val l = lines[i]
            if (purePricePattern.matcher(l).matches()) {
                val p = l.toDoubleOrNull() ?: continue
                if (p in 0.10..999.99) return Pair(i, p)
            }
        }
        return null
    }

    private fun pickQuantity(prices: List<Double>, qtyCandidates: List<Int>): String {
        if (qtyCandidates.isEmpty()) return "1"
        if (prices.size < 2) return "1"

        val unit = prices.minOrNull() ?: return "1"
        val subtotal = prices.maxOrNull() ?: return "1"
        val ratio = subtotal / unit

        for (q in qtyCandidates) {
            if (Math.abs(ratio - q) < 0.1) return q.toString()
        }
        return "1"
    }

    private fun looksLikeName(line: String): Boolean {
        val t = line.trim()
        if (t.length < 4) return false
        if (!t.matches(Regex(".*[A-Za-z]{2,}.*"))) return false
        if (t.matches(Regex("^[\\d\\s.]+$"))) return false
        if (t.matches(Regex("^#[\\d]+.*"))) return false
        if (t.matches(Regex("^\\d+\\s*\\..*"))) return false
        return true
    }

    private fun cleanName(raw: String): String {
        var name = raw.trim()
        name = name.replace(Regex("^[【\\[][^】\\]]*[】\\]]\\s*"), "")
        name = name.replace(Regex("^\\d{3,}\\s+"), "")
        name = name.replace(Regex("\\s+R[MHJN]\\s*$"), "")
        name = name.replace(Regex("\\s+"), " ")
        return name.trim()
    }

    private fun extractAllPrices(line: String): List<Double> {
        val result = mutableListOf<Double>()
        val m = anyPricePattern.matcher(line)
        while (m.find()) {
            val p = m.group(1)?.toDoubleOrNull() ?: continue
            if (p > 0.0 && p < 999.99) result.add(p)
        }
        return result
    }

    private fun isTotalLine(lower: String): Boolean {
        val t = lower.trim()
        val keys = listOf(
            "total products", "grand total", "grand iotal",
            "gränd total", "gand total", "grand jotal", "grarnd total",
            "total praducts", "total praduct",
            "total sales", "total amount", "net total", "jumlah"
        )
        if (t == "total" || t.startsWith("total ") || t.startsWith("total:")) return true
        return keys.any { t == it || t.startsWith("$it ") || t.startsWith("$it:") }
    }

    private fun isStopLine(lower: String): Boolean {
        val t = lower.trim()
        if (t.matches(Regex("^[-=_*#.\\s]+$"))) return true
        if (t.length < 3) return true

        val exactSkipWords = listOf(
            "desc", "description", "item", "items", "name",
            "qty", "quantity", "amount", "amt", "price",
            "total", "subtotal", "sub-total", "cash", "change",
            "credit", "debit", "rounding", "balance",
            "receipi", "recelpt", "receip",
            "rm"
        )
        if (t in exactSkipWords) return true

        val skipStarts = listOf(
            "receipt no", "invoice", "order no", "order number",
            "order time", "order date", "receipt date",
            "cashier", "terminal", "pos:", "staff",
            "payment", "bill", "rounding", "change", "cash",
            "credit", "debit", "mydebit", "duitnow", "duit", "tng",
            "card", "exp date", "appr", "ref num", "batch num",
            "mid", "tid", "aid", "tvr", "host", "trace",
            "e-invoice", "einvoice", "visit", "url", "http", "www",
            "thank", "welcome", "keep the invoice",
            "lot ", "taman", "jalan", "seberang", "pulau",
            "klang", "selangor", "penang", "perai",
            "ssm", "no.", "no:", "ref-1", "biller",
            "feature:", "unit price",
            "local[", "dine-in", "take away", "takeaway",
            "date/time", "date", "time", "tel", "phone",
            "type", "sale", "app crypt", "appl aid", "rref",
            "price(rm)", "qty", "quantity", "sub-total",
            "address", "name", "since", "mixue", "#",
            "grand total", "grand iotal",
            "sub total", "subtotal",
            "rounding", "bill inclusive", "service tax",
            "item name", "credit / debit", "payment type",
            "restaurant no", "recelpt",
            "your order", "texas chicken",
            "seberang perai", "aeon bukit",
            "bukit mertajam", "pulau pinang",
            "sstid", "lot g", "lot no",
            "card transaction",
            "menu rahmah", "rah mah", "rahmah",
            "drinking water", "drink water", "air minuman",
            "roundin", "round1n", "roundlng",

            "familymart", "family mart", "familiymart",
            "sales invoice", "desc", "descr",

            "your order no", "your order no :", "your order no:",
            "vo", "recelpt", "receipi", "receip", "receipt",
            "restaulant no", "restaurant n0", "restaulant n0",
            "restaurant no:", "restaulant no:",

            "1004-bant", "1004-banting",
            "receipt amount", "receipt number",
            "598040",

            // Texas Chicken camera variants
            "restauant no", "restauant n0",
            "restautant no", "restautant n0",
            "restaufant no", "restaufant n0",
            "payment iype",
            "pate & fime",
            "batch nun", "batch nu", "batch num",
            "terminal id",
            "bill inclusl", "bill inclusi", "bill inclusive",
            "bill inciuslve", "bill incdusl",
            "gränd total", "grand jotal", "gand total",
            "card irasacion", "card iranaaction",
            "card tlanaacti", "card iransaction",
            "credit / debit cad", "credit/debit cad",
            "|credit",
            "it id:", "llerminal", "'date &",
            "receipi", "recetpi", "reecelpt",
            "sst id", "sstid",
            "o00001", "o000019", "o0000 19",
            "000001", "000001909", "90018014671",

            "paynent", "paynent iype",
            "cand nunbe", "cand numbe", "cand nae", "cand nunber",
            "fxp date", "fxp",
            "appr cod", "appr code",
            "merchant i0", "merchant io", "merchant id",
            "ierminal",
            "card tlanaaction", "card tlansaction",
            "card tranaaction", "card naration",
            "ser vice", "ser vice lax",
            "cand nunbe.", "fxp date.",

            "pate &",
            "grarnd",
            "cad ira",
            "cad naration",
            "nlomation",
            "ilerminal",
            "ipaynment",
            "ipaynent",

            "paysnent",
            "|csedit",
            "bil incus",
            "bil incu",
            "ser vlce",
            "restaunant",
            "rechipi",

            "unit pice",
            "zuantity",
            "featute",
            "nctal",
            "nounal",
            "noal",
            "total praducts",
            "praducts",
            "nunber",
            "tinie",
            "oider",
            "mertajan",
            "irond",
            "lloot",
            "addres5",
            "duitnoww",
            "duitno",
            "solajatoterprie"
        )
        if (skipStarts.any { t.startsWith(it) }) return true

        if (t.contains("mixue")) return true
        if (t.contains("duitnow") || t.contains("duit")) return true
        if (t.contains("texas chicken")) return true

        val containsAnywhere = listOf(
            "familymart", "family mart", "familiymart",
            "jalan ", "taman ", "bukit ", "lorong ", "persiaran ",
            "shah alam", "kuala lumpur", "selangor", "penang",
            "sales invoice", "welcome to the family",
            "ql maximcome", "ql maxincome", "maxincome",
            "ice cream & tea", "since 199",
            "your order no", "order no :", "order no:",
            "receipi", "recelpt",
            "restaulant no", "restaurant no:", "restaurant n0",
            "restaulant n0", "restautant no", "restaufant no",
            "receipt number", "receipt amount",
            "bant ing", "banting",
            "invoice no",
            "e-invoice",
            "restauant no",
            "payment iype",
            "pate & fime",
            "batch nun", "batch nu",
            "bill inclusl", "bill inclusi ve", "bill inciuslve",
            "gränd total", "grand jotal",
            "card irasacion", "card iranaaction", "card tlanaacti",
            "card iransaction", "fanaacion", "tlanaacti",
            "lntonatio", "intomhation", "informationn",
            "irasacion", "iranaaction", "iransaction",
            "credit / debit cad", "credit/debit cad",
            "receipi", "recetpi", "reecelpt",
            "llerminal", "'date &", "date & time",
            "sst id",

            "paynent",
            "cand nunbe", "cand numbe", "cand nae",
            "fxp date",
            "appr cod",
            "merchant i0",
            "ierminal",
            "tlanaaction", "tranaaction",
            "ser vice",
            "card naration",

            "grarnd",
            "pate &",
            "nlomation",
            "ilerminal",
            "ipaynment",

            "paysnent",
            "csedit",
            "incuslve",
            "ser vlce",
            "rasaction",
            "ntomation",

            "zuantity",
            "featute",
            "nctal",
            "nounal",
            "praducts",
            "oider",
            "mertajan",
            "duitnoww",
            "total praduct"
        )
        if (containsAnywhere.any { t.contains(it) }) return true

        if (t.matches(Regex("^\\d{1,2}[-/]\\d{1,2}[-/]\\d{2,4}.*"))) return true
        if (t.matches(Regex("^\\d{4}-\\d{2}-\\d{2}.*"))) return true
        if (t.matches(Regex("^\\d{1,2}:\\d{2}.*"))) return true

        if (t.contains("sdn") && (t.contains("bhd") || t.contains("s/b"))) return true
        if (t.contains("enterprise")) return true
        if (t.contains("bhd")) return true

        return false
    }

    fun detectStoreName(text: String): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val upperText = text.uppercase()

        val knownBrands = listOf(
            "MIXUE" to "Mixue",
            "99 SPEED MART" to "99 Speedmart",
            "99 SPEEDMART" to "99 Speedmart",
            "SPEED MART" to "99 Speedmart",
            "LOTUS" to "Lotus's",
            "TESCO" to "Lotus's",
            "FAMILYMART" to "FamilyMart",
            "FAMILY MART" to "FamilyMart",
            "FAMILIYMART" to "FamilyMart",
            "FANILY MART" to "FamilyMart",
            "FANI LYMART" to "FamilyMart",
            "TEXAS CHICKEN" to "Texas Chicken",
            "MCDONALD" to "McDonald's",
            "MCD" to "McDonald's",
            "KFC" to "KFC",
            "PIZZA HUT" to "Pizza Hut",
            "DOMINO" to "Domino's Pizza",
            "STARBUCKS" to "Starbucks",
            "ZUS" to "ZUS Coffee",
            "TEALIVE" to "Tealive",
            "SECRET RECIPE" to "Secret Recipe",
            "SUBWAY" to "Subway",
            "BURGER KING" to "Burger King",
            "AEON" to "AEON",
            "GIANT" to "Giant",
            "MYDIN" to "Mydin",
            "ECONSAVE" to "Econsave",
            "7-ELEVEN" to "7-Eleven",
            "7 ELEVEN" to "7-Eleven",
            "GUARDIAN" to "Guardian",
            "WATSON" to "Watsons",
            "WATSONS" to "Watsons",
            "PETRONAS" to "Petronas",
            "SHELL" to "Shell",
            "PETRON" to "Petron",
            "CALTEX" to "Caltex",
            "GRAB" to "Grab",
            "MR DIY" to "MR.DIY"
        )

        for ((keyword, brand) in knownBrands) {
            if (upperText.contains(keyword)) return brand
        }

        for (i in 0 until minOf(5, lines.size)) {
            val trimmed = lines[i].trim()
            if (trimmed.length < 4) continue
            if (trimmed.matches(Regex("^[\\d\\s.,-]+$"))) continue
            if (!trimmed.matches(Regex(".*[A-Za-z]{3,}.*"))) continue
            if (trimmed.lowercase().contains("receipt")) continue
            if (trimmed.lowercase().contains("invoice")) continue
            if (trimmed.lowercase().contains("tax")) continue
            if (trimmed == trimmed.uppercase() && trimmed.split(" ").size <= 4) {
                return cleanedBrand(trimmed)
            }
        }

        return "Receipt"
    }

    private fun cleanedBrand(raw: String): String {
        var name = raw.trim()
        name = name.replace(Regex("\\s*\\(.*\\)\\s*$"), "")
        name = name.replace(Regex("\\s*(SDN|S/B|S/BHD|BHD)\\..*$", RegexOption.IGNORE_CASE), "")
        name = name.replace(Regex("\\s+"), " ")
        return name.trim().trimEnd('.', ',', '-').ifEmpty { "Receipt" }
    }

    // ============================================================
    //  Price clean-up (offline accuracy)
    // ============================================================

    /** A price as OCR may misread it: "7.9O", "7,90", "1S.50", "7. 90", optional "RM". */
    private val fuzzyPriceLine = Regex(
        "^(R[MHJN]\\s*)?([0-9OoSBlI|]{1,4})\\s*[.,]\\s*([0-9OoSBlI|]{2})(?:\\s+(?:SR|ZR|SV|S|Z|T|E|X|\\*))?$"
    )

    private fun ocrDigits(s: String) = buildString {
        for (c in s) append(
            when (c) {
                'O', 'o' -> '0'
                'S' -> '5'
                'B' -> '8'
                'l', 'I', '|' -> '1'
                else -> c
            }
        )
    }

    /**
     * Fixes OCR mix-ups in lines that are only a price, so the parser can use
     * them: letters that look like digits, a comma or space around the decimal
     * point, and tax codes after the price ("7.90 SR"). Other lines (item
     * names, addresses) are left alone; at least one real digit is required.
     */
    internal fun normalizePriceLine(line: String): String {
        val t = line.trim()
        val m = fuzzyPriceLine.matchEntire(t) ?: return line
        val (prefix, whole, cents) = m.destructured
        if (!(whole + cents).any { it.isDigit() }) return line
        val fixed = "${ocrDigits(whole)}.${ocrDigits(cents)}"
        return if (prefix.isNotEmpty()) "RM $fixed" else fixed
    }

    data class ParseResult(
        val items: List<ReceiptItem>,
        val receiptTotal: Double,
        /** True when a printed total was found (not just the items added up). */
        val totalFound: Boolean = false,
        /** Added charges + rounding, so items + extras should equal the total. */
        val extras: Double = 0.0,
        /** Tax / service charge lines found on the receipt. */
        val charges: List<com.jmti.budgetfy.models.ReceiptCharge> = emptyList()
    )
}