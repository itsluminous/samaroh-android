package com.itsluminous.samaroh.core.database

import androidx.room.TypeConverter
import com.itsluminous.samaroh.core.model.BookingSource
import com.itsluminous.samaroh.core.model.BookingStatus
import com.itsluminous.samaroh.core.model.ExpenseDirection
import com.itsluminous.samaroh.core.model.MemberPermissions
import com.itsluminous.samaroh.core.model.MemberStatus
import com.itsluminous.samaroh.core.model.PaymentMethod
import com.itsluminous.samaroh.core.model.ReminderKind
import com.itsluminous.samaroh.core.model.ReminderStatus
import com.itsluminous.samaroh.core.model.TxnType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Room type converters. Storage conventions:
 * - [Instant] → epoch milliseconds (`INTEGER`) — cheap ordering and cursor comparisons;
 * - [LocalDate]/[LocalTime] → ISO-8601 `TEXT` — lexicographic order equals chronological order;
 * - enums → their Postgres wire string;
 * - [MemberPermissions] → the same JSON document stored in `business_members.permissions`.
 */
class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun instantToLong(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter fun longToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    @TypeConverter fun localTimeToString(value: LocalTime?): String? = value?.toString()

    @TypeConverter fun stringToLocalTime(value: String?): LocalTime? = value?.let(LocalTime::parse)

    @TypeConverter fun memberStatusToString(value: MemberStatus?): String? = value?.wire

    @TypeConverter fun stringToMemberStatus(value: String?): MemberStatus? = value?.let(MemberStatus::fromWire)

    @TypeConverter fun bookingStatusToString(value: BookingStatus?): String? = value?.wire

    @TypeConverter fun stringToBookingStatus(value: String?): BookingStatus? = value?.let(BookingStatus::fromWire)

    @TypeConverter fun paymentMethodToString(value: PaymentMethod?): String? = value?.wire

    @TypeConverter fun stringToPaymentMethod(value: String?): PaymentMethod? = value?.let(PaymentMethod::fromWire)

    @TypeConverter fun reminderStatusToString(value: ReminderStatus?): String? = value?.wire

    @TypeConverter fun stringToReminderStatus(value: String?): ReminderStatus? = value?.let(ReminderStatus::fromWire)

    @TypeConverter fun reminderKindToString(value: ReminderKind?): String? = value?.wire

    @TypeConverter fun stringToReminderKind(value: String?): ReminderKind? = value?.let(ReminderKind::fromWire)

    @TypeConverter fun txnTypeToString(value: TxnType?): String? = value?.wire

    @TypeConverter fun stringToTxnType(value: String?): TxnType? = value?.let(TxnType::fromWire)

    @TypeConverter fun expenseDirectionToString(value: ExpenseDirection?): String? = value?.wire

    @TypeConverter fun stringToExpenseDirection(value: String?): ExpenseDirection? = value?.let(ExpenseDirection::fromWire)

    @TypeConverter fun bookingSourceToString(value: BookingSource?): String? = value?.wire

    @TypeConverter fun stringToBookingSource(value: String?): BookingSource? = value?.let(BookingSource::fromWire)

    @TypeConverter
    fun permissionsToString(value: MemberPermissions?): String? = value?.let { json.encodeToString(MemberPermissions.serializer(), it) }

    @TypeConverter
    fun stringToPermissions(value: String?): MemberPermissions? = value?.let { json.decodeFromString(MemberPermissions.serializer(), it) }

    @TypeConverter
    fun stringListToString(value: List<String>?): String? = value?.let { json.encodeToString(ListSerializer(String.serializer()), it) }

    @TypeConverter
    fun stringToStringList(value: String?): List<String>? = value?.let { json.decodeFromString(ListSerializer(String.serializer()), it) }
}
