# R8 keeps the app auditable: shrink but do not obfuscate (open-source project).
-dontobfuscate

# kotlinx.serialization: keep generated serializer lookups for model classes.
-keepclassmembers class com.itsluminous.samaroh.core.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.itsluminous.samaroh.core.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}
