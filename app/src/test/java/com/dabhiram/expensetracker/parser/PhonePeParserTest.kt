package com.dabhiram.expensetracker.parser

import org.junit.Assert.*
import org.junit.Test

class PhonePeParserTest {

    private val now = System.currentTimeMillis()

    // ── SHOULD NOT PARSE ───────────────────────────────────────────────────

    @Test
    fun `PhonePe home screen with history does not parse`() {
        val homeScreen = listOf(
            "PhonePe",
            "Send Money",
            "Recharge",
            "History",
            "Swiggy",
            "₹349 Sent",
            "Yesterday",
            "Rapido",
            "₹80 Sent",
            "2 days ago",
        )
        assertNull("PhonePe home should not parse", PhonePeParser.parse(homeScreen, now))
    }

    @Test
    fun `PhonePe transaction detail does not parse`() {
        val detail = listOf(
            "Transaction Successful",  // note: NOT "Payment successful" — this is a historical detail
            "Swiggy",
            "₹349",
            "21 May 2024",
            "swiggy@ybl",
            "Transaction ID",
            "407301234567",
        )
        // "Transaction Successful" is not in our keyword list — should NOT match
        assertNull("Historical detail should not parse", PhonePeParser.parse(detail, now))
    }

    @Test
    fun `PhonePe payment pending screen does not parse`() {
        val pending = listOf(
            "Processing Payment",
            "₹500",
            "Swiggy",
            "swiggy@ybl",
        )
        assertNull(PhonePeParser.parse(pending, now))
    }

    @Test
    fun `PhonePe enter amount screen does not parse`() {
        val enterAmount = listOf(
            "Enter Amount",
            "₹",
            "Swiggy",
            "swiggy@ybl",
            "Pay",
        )
        assertNull(PhonePeParser.parse(enterAmount, now))
    }

    @Test
    fun `empty nodes returns null`() {
        assertNull(PhonePeParser.parse(emptyList(), now))
    }

    // ── SHOULD PARSE ───────────────────────────────────────────────────────

    @Test
    fun `payment successful parses correctly`() {
        val success = listOf(
            "Payment Successful",
            "₹349",
            "Swiggy",
            "swiggy@ybl",
            "UPI Ref: 407301234567",
            "Done",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals("407301234567", result!!.transactionRef)
        assertEquals("Swiggy", result.recipientName)
        assertEquals("swiggy@ybl", result.recipientVpa)
        assertEquals(0, "349".toBigDecimal().compareTo(result.amount))
    }

    @Test
    fun `transfer successful parses correctly`() {
        val success = listOf(
            "Transfer Successful",
            "₹2,000",
            "Rahul",
            "9876543210@oksbi",
            "UTR: 123456789012",
            "Done",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals("123456789012", result!!.transactionRef)
        assertEquals(0, "2000".toBigDecimal().compareTo(result.amount))
    }

    @Test
    fun `paid successfully parses correctly`() {
        val success = listOf(
            "Paid successfully",
            "₹80",
            "Rapido",
            "rapido@hdfcbank",
            "Ref No: 998877665544",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals("998877665544", result!!.transactionRef)
        assertEquals("Rapido", result.recipientName)
    }

    @Test
    fun `money sent parses correctly`() {
        val success = listOf(
            "Money sent",
            "₹5,000",
            "Dad",
            "9012345678@sbi",
            "UPI Ref No. 555444333222",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals("555444333222", result!!.transactionRef)
    }

    @Test
    fun `sent successfully parses correctly`() {
        val success = listOf(
            "₹100 sent successfully",
            "To Chai Corner",
            "chaicorner@paytm",
            "Ref: 111222333444",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals("111222333444", result!!.transactionRef)
    }

    @Test
    fun `payment done variant parses correctly`() {
        val success = listOf(
            "Payment done",
            "₹250",
            "Zomato",
            "zomato@icici",
            "Transaction ID: 667788990011",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals("667788990011", result!!.transactionRef)
    }

    @Test
    fun `without UPI ref falls back to generated ref`() {
        val success = listOf(
            "Payment Successful",
            "₹50",
            "Auto Driver",
            "9012345678@jio",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertTrue(result!!.transactionRef.startsWith("PHONEPE_"))
    }

    @Test
    fun `case insensitive phrase matching`() {
        val success = listOf(
            "PAYMENT SUCCESSFUL",
            "₹500",
            "BigBasket",
            "bigbasket@razorpay",
            "UPI Ref: 334455667788",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull("Case-insensitive match required", result)
    }

    @Test
    fun `amount with decimal parses correctly`() {
        val success = listOf(
            "Payment Successful",
            "₹199.99",
            "Hotstar",
            "hotstar@hdfcbank",
            "UPI Ref: 445566778899",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals(0, "199.99".toBigDecimal().compareTo(result!!.amount))
    }

    @Test
    fun `source app is PHONEPE`() {
        val success = listOf(
            "Payment Successful",
            "₹100",
            "Test",
            "test@ybl",
            "UPI Ref: 112233445566",
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals(com.dabhiram.expensetracker.data.model.SourceApp.PHONEPE, result!!.sourceApp)
    }

    @Test
    fun `twelve digit number used as ref when no label prefix`() {
        val success = listOf(
            "Payment Successful",
            "₹500",
            "Swiggy",
            "swiggy@ybl",
            "407301234567",   // bare 12-digit number, no "UPI Ref" label
        )
        val result = PhonePeParser.parse(success, now)
        assertNotNull(result)
        assertEquals("407301234567", result!!.transactionRef)
    }
}
