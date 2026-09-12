package com.dabhiram.expensetracker.categorizer

import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.MerchantRule
import com.dabhiram.expensetracker.data.model.VpaCategory

sealed class CategorizationResult {
    data class Categorized(val category: String, val by: CategorizedBy) : CategorizationResult()
    data class NeedsUserInput(val suggestedCategories: List<String>) : CategorizationResult()
}

object Categorizer {

    private val PHONE_VPA_PATTERN = Regex("""^\d{10}@""")
    private val BUSINESS_VPA_SUFFIXES = setOf(
        "@razorpay", "@paytm", "@ybl", "@apl", "@axl", "@hdfcbank", "@icici",
        "@sbi", "@upi", "@okicici", "@oksbi", "@okaxis", "@okhdfc", "@idfcbank",
        "@indus", "@kotak", "@rbl", "@federal", "@aubank"
    )

    fun categorize(
        vpa: String?,
        recipientName: String,
        vpaCacheEntries: List<VpaCategory>,
        merchantRules: List<MerchantRule>,
        availableCategories: List<String>
    ): CategorizationResult {
        if (vpa != null) {
            val cached = vpaCacheEntries.find { it.vpa.equals(vpa, ignoreCase = true) }
            if (cached != null) {
                return CategorizationResult.Categorized(cached.category, CategorizedBy.AUTO_VPA)
            }
        }

        val merchant = matchMerchantRule(vpa, recipientName, merchantRules)
        if (merchant != null) {
            return CategorizationResult.Categorized(merchant, CategorizedBy.AUTO_MERCHANT)
        }

        if (vpa != null && PHONE_VPA_PATTERN.containsMatchIn(vpa)) {
            return CategorizationResult.NeedsUserInput(
                suggest(listOf("Personal Transfers", "Food", "Travel"), availableCategories)
            )
        }

        val suffix = vpa?.let { v -> BUSINESS_VPA_SUFFIXES.find { v.endsWith(it, ignoreCase = true) } }
        if (suffix != null) {
            return CategorizationResult.NeedsUserInput(
                suggest(listOf("Shopping", "Food", "Utilities"), availableCategories)
            )
        }

        return CategorizationResult.NeedsUserInput(
            suggest(listOf("Food", "Travel", "Shopping"), availableCategories)
        )
    }

    // Suggestions are hardcoded, well-known categories, but the user can
    // delete any category — never offer one that no longer exists. Falls
    // back to whatever categories are actually available if none of the
    // preferred suggestions survived.
    private fun suggest(preferred: List<String>, availableCategories: List<String>): List<String> {
        val filtered = preferred.filter { p -> availableCategories.any { it.equals(p, ignoreCase = true) } }
        return filtered.ifEmpty { availableCategories.take(3) }
    }

    private fun matchMerchantRule(
        vpa: String?,
        recipientName: String,
        rules: List<MerchantRule>
    ): String? {
        for (rule in rules) {
            val pattern = rule.pattern.lowercase()
            if (vpa != null && vpa.lowercase().contains(pattern)) return rule.category
            if (recipientName.lowercase().contains(pattern)) return rule.category
        }
        return null
    }
}

// Compact (name/vpa → category) history to hand an LLM as context — lets it
// categorize by analogy even when a recipient's VPA never repeats (e.g. a
// different auto-rickshaw driver's VPA every ride).
fun knownMappings(vpaCache: List<VpaCategory>, limit: Int = 20): List<Pair<String, String>> =
    vpaCache
        .sortedByDescending { it.lastUsed }
        .take(limit)
        .map { (it.recipientName.ifBlank { it.vpa }) to it.category }
