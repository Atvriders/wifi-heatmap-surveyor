# ---------------------------------------------------------------------------
# R8 / ProGuard keep rules for the release (minified + resource-shrunk) build.
# ---------------------------------------------------------------------------

# --- kotlinx.serialization -------------------------------------------------
# Used for Navigation Compose type-safe routes (@Serializable route classes).
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class com.atvriders.wifiheatmap.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# --- Room ------------------------------------------------------------------
# Keep the generated DAO/database implementations and the entities.
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep class com.atvriders.wifiheatmap.data.**_Impl { *; }
-dontwarn androidx.room.paging.**

# --- Kotlin / coroutines ---------------------------------------------------
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.annotations.**
