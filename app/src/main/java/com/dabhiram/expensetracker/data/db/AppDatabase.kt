package com.dabhiram.expensetracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dabhiram.expensetracker.data.db.dao.CategoryDao
import com.dabhiram.expensetracker.data.db.dao.MerchantRuleDao
import com.dabhiram.expensetracker.data.db.dao.TransactionDao
import com.dabhiram.expensetracker.data.db.dao.VpaCategoryDao
import com.dabhiram.expensetracker.data.model.Category
import com.dabhiram.expensetracker.data.model.MerchantRule
import com.dabhiram.expensetracker.data.model.Transaction
import com.dabhiram.expensetracker.data.model.VpaCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [Transaction::class, VpaCategory::class, MerchantRule::class, Category::class],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao
    abstract fun vpaCategoryDao(): VpaCategoryDao
    abstract fun merchantRuleDao(): MerchantRuleDao
    abstract fun categoryDao(): CategoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `categories` (`name` TEXT NOT NULL, `sortOrder` INTEGER NOT NULL, PRIMARY KEY(`name`))"
                )
                defaultCategories().forEachIndexed { index, name ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO `categories` (`name`, `sortOrder`) VALUES (?, ?)",
                        arrayOf<Any>(name, index)
                    )
                }
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN splitMyShare TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN splitPeopleCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE transactions SET category = 'Lent' WHERE category = 'Paid on Behalf'")
                db.execSQL("UPDATE categories SET name = 'Lent' WHERE name = 'Paid on Behalf'")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN budget TEXT")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "expense_tracker.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .addCallback(object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            INSTANCE?.let { database ->
                                CoroutineScope(Dispatchers.IO).launch {
                                    database.merchantRuleDao().insertAll(defaultMerchantRules())
                                    database.categoryDao().insertAll(
                                        defaultCategories().mapIndexed { index, name -> Category(name, index) }
                                    )
                                }
                            }
                        }
                    })
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

// Seed values for both a fresh install (Callback.onCreate) and an upgrade
// from schema v1, which had no categories table at all (MIGRATION_1_2).
private fun defaultCategories(): List<String> = listOf(
    "Food",
    "Groceries",
    "Travel",
    "Utilities",
    "Entertainment",
    "Shopping",
    "Personal Transfers",
    "Lent",
    "Bills & Rent",
    "Health",
    "Education",
    "Other"
)

private fun defaultMerchantRules(): List<MerchantRule> = listOf(
    MerchantRule("swiggy", "Food", false),
    MerchantRule("zomato", "Food", false),
    MerchantRule("eatsure", "Food", false),
    MerchantRule("blinkit", "Groceries", false),
    MerchantRule("zepto", "Groceries", false),
    MerchantRule("instamart", "Groceries", false),
    MerchantRule("bigbasket", "Groceries", false),
    MerchantRule("jiomart", "Groceries", false),
    MerchantRule("irctc", "Travel", false),
    MerchantRule("uber", "Travel", false),
    MerchantRule("ola", "Travel", false),
    MerchantRule("rapido", "Travel", false),
    MerchantRule("redbus", "Travel", false),
    MerchantRule("makemytrip", "Travel", false),
    MerchantRule("goibibo", "Travel", false),
    MerchantRule("cleartrip", "Travel", false),
    MerchantRule("indigo", "Travel", false),
    MerchantRule("airasia", "Travel", false),
    MerchantRule("bookmyshow", "Entertainment", false),
    MerchantRule("paytm.movies", "Entertainment", false),
    MerchantRule("pvr", "Entertainment", false),
    MerchantRule("inox", "Entertainment", false),
    MerchantRule("netflix", "Entertainment", false),
    MerchantRule("hotstar", "Entertainment", false),
    MerchantRule("spotify", "Entertainment", false),
    MerchantRule("bescom", "Utilities", false),
    MerchantRule("electricity", "Utilities", false),
    MerchantRule("bses", "Utilities", false),
    MerchantRule("msedcl", "Utilities", false),
    MerchantRule("tatapower", "Utilities", false),
    MerchantRule("airtel", "Utilities", false),
    MerchantRule("jio", "Utilities", false),
    MerchantRule("vodafone", "Utilities", false),
    MerchantRule("bsnl", "Utilities", false),
    MerchantRule("amazon", "Shopping", false),
    MerchantRule("flipkart", "Shopping", false),
    MerchantRule("myntra", "Shopping", false),
    MerchantRule("nykaa", "Shopping", false),
    MerchantRule("meesho", "Shopping", false),
    MerchantRule("1mg", "Health", false),
    MerchantRule("apollopharmacy", "Health", false),
    MerchantRule("netmeds", "Health", false),
    MerchantRule("practo", "Health", false),
    MerchantRule("udemy", "Education", false),
    MerchantRule("coursera", "Education", false),
    MerchantRule("byju", "Education", false),
    MerchantRule("unacademy", "Education", false)
)
