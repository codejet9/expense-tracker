# UPI Expense Tracker

A personal Android app that automatically captures and categorizes every UPI payment you make through Google Pay or PhonePe, without you having to do anything manually. It sits quietly in the background, picks up transactions the moment they happen, and gives you a clean view of where your money is going.

## Why I Built This

Most expense tracking apps require you to either connect your bank account (which feels invasive) or manually enter transactions (which nobody actually does consistently). UPI apps like GPay and PhonePe show you a nice notification every time a payment goes through. This app reads those notifications using Android's Accessibility API and logs the transaction automatically. No bank credentials, no manual entry, no subscription fees.

## What It Does

**Automatic Transaction Capture**

The moment you pay via GPay or PhonePe, the app captures the amount, recipient name, VPA (UPI ID), and timestamp. It runs as a foreground accessibility service so it works even when the app is in the background. Duplicate detection is built in so the same transaction never gets recorded twice.

**AI Powered Categorization**

When a new transaction comes in, the app tries to figure out what category it belongs to. It first checks if you have a saved rule for that merchant or VPA. If not, it calls an LLM (OpenAI, Groq, or Gemini depending on which API key you have configured) to categorize it based on the merchant name. Categories like Food, Groceries, Transport, Shopping, Entertainment, Bills, and so on. You can also correct any category manually and the app remembers your preference for that merchant going forward.

**AI Spending Insights**

The Reports screen shows you an AI generated summary of your spending patterns. For the current week it compares against last week, and for the current month it compares against last month. The insights are cached locally so the app is not making LLM calls on every screen open. It only refreshes insights when you actually add, edit, or delete a transaction. Custom date ranges always get a fresh analysis.

**Transaction Splitting**

If you paid for a group dinner or covered someone else's expenses, you can mark a transaction as split. You enter how much was your personal share, how much was paid on behalf of others, and how many people are splitting. The app creates separate entries for each person's share under a "Paid on Behalf" category so your actual personal spend stays accurate.

**Reports and Drilldown**

The Reports screen breaks down your spending by category with totals, shows your top merchants, and highlights the biggest single expense in the period. You can tap into any category to see the individual transactions within it. Pagination is handled so even if you have hundreds of transactions in a month it does not feel slow. The view supports This Week, This Month, and a custom date range picker.

**Screenshot Import**

If you have an old screenshot of a payment confirmation from GPay or PhonePe, you can share it directly into the app. It uses OCR via an LLM to extract the transaction details and adds it to your records.

**Merchant Rules**

You can set up rules like "whenever the VPA is zomato@okicici, always categorize as Food". These rules are checked before any LLM call so common merchants get categorized instantly without any API cost.

## Tech Stack

Built entirely in Kotlin with Jetpack Compose for the UI. Room is used for local storage with proper migrations. All LLM calls go through a single multi-provider client that tries OpenAI first, then Groq, then Gemini depending on what keys are configured. The app uses coroutines and StateFlow throughout for reactive UI updates.

The accessibility service parses GPay and PhonePe notification text using regex patterns tailored to each app's notification format. No screen scraping or OCR is involved for live transactions, only for the screenshot import feature.

Spending insights are cached in SharedPreferences with a dual expiry condition: the cache is considered stale if any transaction changed after it was computed, or if the current period boundary (week start or month start) is newer than when the cache was written. This means insights automatically refresh at the start of a new week or month even if you have not touched any transactions.

## Getting Started

**Prerequisites**

You need Android Studio (Ladybug or newer) and a device or emulator running Android 8.0 or above. The app targets Android 14 but works on older versions too.

**Building**

Clone the repo and open it in Android Studio. It should sync Gradle automatically. Connect your Android device via USB or wireless ADB and hit Run.

**Setting Up**

On first launch go to Settings and enable the Accessibility Service. Android will take you to the system accessibility settings where you need to find "Expense Tracker" and turn it on. Without this the automatic capture will not work.

Also in Settings you can add your API keys for OpenAI, Groq, or Gemini. You only need one of them. Groq has a generous free tier if you want to get started without spending anything. The keys are stored locally on your device in SharedPreferences and never sent anywhere except to the respective API endpoint when making a categorization or insights call.

**API Keys**

You can get free or low cost API keys from:

* Groq: groq.com (free tier, fast)
* OpenAI: platform.openai.com
* Google Gemini: aistudio.google.com

The app tries them in the order OpenAI, Groq, Gemini. Whichever keys you have configured, it picks the first available one.

## Permissions

The app asks for the following permissions:

* Accessibility Service: to read UPI payment notifications from GPay and PhonePe
* Post Notifications: to show a persistent notification while the accessibility service is running (required by Android for foreground services)
* Internet: to call LLM APIs for categorization and insights
* Receive Boot Completed: to restart the accessibility service automatically after a device reboot
* Ignore Battery Optimizations: to prevent Android from killing the background service

## Privacy

All your transaction data stays on your device. The only things that leave your device are the merchant names and spending summaries sent to whichever LLM API you configure, for categorization and insights. No raw transaction data, no VPAs, no amounts are sent to any server other than the LLM API you have chosen. The app has no backend of its own.

## Project Structure

```
app/src/main/java/com/dabhiram/expensetracker/
    categorizer/        Rule based and LLM categorization logic
    data/               Room database, DAOs, models, event bus, cache
    llm/                Multi-provider LLM client, analyzers, API key manager
    notification/       Notification channels and transaction notifications
    parser/             GPay and PhonePe notification text parsers
    service/            UPI accessibility service
    share/              Screenshot share intent handler
    ui/                 Compose screens, view models, navigation, theme
    worker/             Background workers for weekly summaries
```

## Known Limitations

The app currently supports Google Pay and PhonePe. Other UPI apps like Paytm or BHIM are not supported yet since each app formats its notification text differently and needs its own parser.

The accessibility service needs to stay on in the background. Some aggressive battery saver modes on devices from certain manufacturers (especially Xiaomi, OnePlus, and some Samsung variants) will kill background services. If you notice transactions getting missed, check your device's battery optimization settings and exclude the app.

## License

MIT. Do whatever you want with it.
