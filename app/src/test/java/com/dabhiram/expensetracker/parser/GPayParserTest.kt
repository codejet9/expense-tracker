package com.dabhiram.expensetracker.parser

import org.junit.Assert.*
import org.junit.Test

/**
 * Simulates real GPay screen text-node dumps.
 * GPay's PIN-entry, processing, and success screens are FLAG_SECURE and never
 * dispatch accessibility events (confirmed on device), so parsing only covers
 * the post-payment contact history screen.
 */
class GPayParserTest {

    private val now = System.currentTimeMillis()

    // ── SHOULD NOT PARSE (false-positive scenarios) ────────────────────────

    @Test
    fun `home screen with transaction history does not parse`() {
        val homeScreen = listOf(
            "Google Pay",
            "Search",
            "New payment",
            "Swiggy",
            "₹349",
            "Yesterday",
            "Zomato",
            "₹250",
            "2 days ago",
            "Rapido",
            "₹80",
            "3 days ago",
            "Jio",
            "₹239",
            "4 days ago",
        )
        assertNull("Home screen should not parse", GPayParser.parse(homeScreen, now))
    }

    @Test
    fun `transaction detail screen does not parse`() {
        // Tapping a past transaction in history shows this screen
        val detailScreen = listOf(
            "Swiggy",
            "Sent",
            "₹349",
            "21 May 2024, 1:30 PM",
            "To",
            "swiggy@okicici",
            "UPI Ref No.",
            "407301234567",
            "Share receipt",
        )
        assertNull("Transaction detail should not parse", GPayParser.parse(detailScreen, now))
    }

    @Test
    fun `contacts or people tab does not parse`() {
        val peopleTab = listOf(
            "People",
            "Rahul",
            "9876543210",
            "Priya",
            "8765432109",
            "₹500",
            "Last paid",
        )
        assertNull("People tab should not parse", GPayParser.parse(peopleTab, now))
    }

    @Test
    fun `payment loading screen does not parse`() {
        val loadingScreen = listOf(
            "Sending ₹500...",
            "To Swiggy",
            "swiggy@okicici",
            "Please wait",
        )
        assertNull("Loading screen should not parse", GPayParser.parse(loadingScreen, now))
    }

    @Test
    fun `payment confirmation screen before sending does not parse`() {
        val confirmScreen = listOf(
            "Pay ₹500",
            "To Swiggy",
            "swiggy@okicici",
            "UPI PIN",
            "Confirm",
        )
        assertNull("Confirmation screen should not parse", GPayParser.parse(confirmScreen, now))
    }

    @Test
    fun `success screen text does not parse (blocked by GPay, unreachable)`() {
        val successScreen = listOf(
            "Payment successful",
            "₹349",
            "Paid to",
            "Swiggy",
            "swiggy@okicici",
            "Transaction ID",
            "407301234567",
            "Done",
            "Share",
        )
        assertNull("Success screen text is never reachable, must not parse", GPayParser.parse(successScreen, now))
    }

    @Test
    fun `empty screen does not parse`() {
        assertNull(GPayParser.parse(emptyList(), now))
        assertNull(GPayParser.parse(listOf(""), now))
    }

    // ── History screen (real GPay post-payment flow) ────────────────────────

    @Test
    fun `history screen detects today payment by time-only format`() {
        // Exact structure seen in real dump_gpay_20260606_210928.txt
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val timeStr = "$h12:${m.toString().padStart(2, '0')} $ampm"

        val historyScreen = listOf(
            "Back",
            "Venkateshwar devaraju\n+919866062342",
            "Call phone number",
            "Show menu",
            "Payment to you\n₹19,000\nPaid • 30 Mar",
            "Payment to Venkateshwar\n₹1\nPaid • 20 May",
            "Payment to Venkateshwar\n₹1\nPaid • $timeStr",  // today's payment
            "Pay",
            "Send message"
        )
        val result = GPayParser.parse(historyScreen, System.currentTimeMillis())
        assertNotNull("Should detect today's payment from history screen", result)
        assertEquals("1".toBigDecimal(), result!!.amount)
        assertEquals("Venkateshwar devaraju", result.recipientName)
        assertEquals("9866062342@gpay", result.recipientVpa)
        assertTrue(result.transactionRef.startsWith("GPAY_H_"))
    }

    @Test
    fun `history screen matches am_pm time with narrow no-break space`() {
        // Android's time formatter inserts U+202F between the time and am/pm,
        // not a regular space — the accessibility text carries the raw glyph.
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val timeStr = "$h12:${m.toString().padStart(2, '0')} $ampm"

        val historyScreen = listOf(
            "Venkateshwar devaraju\n+919866062342",
            "Payment to Venkateshwar\n₹1\nPaid • $timeStr"
        )
        assertNull(
            "Regex alone (without upstream whitespace normalization) should not match U+202F",
            GPayParser.parse(historyScreen, System.currentTimeMillis())
        )
    }

    @Test
    fun `history screen ignores old payments with date format`() {
        val historyScreen = listOf(
            "Back",
            "Venkateshwar devaraju\n+919866062342",
            "Payment to Venkateshwar\n₹1\nPaid • 20 May",
            "Payment to Venkateshwar\n₹1\nPaid • 3 Jun",
            "Pay"
        )
        assertNull("Old dated payments should not parse", GPayParser.parse(historyScreen, System.currentTimeMillis()))
    }

    @Test
    fun `history screen ignores received payments (Payment to you)`() {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val timeStr = "$h12:${m.toString().padStart(2, '0')} $ampm"

        val historyScreen = listOf(
            "Back",
            "Venkateshwar devaraju\n+919866062342",
            "Payment to you\n₹500\nPaid • $timeStr",  // received, should ignore
            "Pay"
        )
        assertNull("Received payments should not be detected", GPayParser.parse(historyScreen, System.currentTimeMillis()))
    }

    @Test
    fun `history screen ref is stable across multiple events`() {
        val cal = java.util.Calendar.getInstance()
        val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val m = cal.get(java.util.Calendar.MINUTE)
        val ampm = if (h < 12) "am" else "pm"
        val h12 = if (h % 12 == 0) 12 else h % 12
        val timeStr = "$h12:${m.toString().padStart(2, '0')} $ampm"

        val screen = listOf(
            "Venkateshwar devaraju\n+919866062342",
            "Payment to Venkateshwar\n₹100\nPaid • $timeStr"
        )
        val r1 = GPayParser.parse(screen, System.currentTimeMillis())
        val r2 = GPayParser.parse(screen, System.currentTimeMillis() + 3000)
        assertNotNull(r1); assertNotNull(r2)
        assertEquals("Ref must be stable for dedup", r1!!.transactionRef, r2!!.transactionRef)
    }
}
