package com.dabhiram.expensetracker.data.db

import androidx.room.TypeConverter
import com.dabhiram.expensetracker.data.model.CategorizedBy
import com.dabhiram.expensetracker.data.model.SourceApp

class Converters {

    @TypeConverter
    fun sourceAppToString(value: SourceApp): String = value.name

    @TypeConverter
    fun stringToSourceApp(value: String): SourceApp = SourceApp.valueOf(value)

    @TypeConverter
    fun categorizedByToString(value: CategorizedBy): String = value.name

    @TypeConverter
    fun stringToCategorizedBy(value: String): CategorizedBy = CategorizedBy.valueOf(value)
}
