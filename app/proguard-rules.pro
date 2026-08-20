# Release builds set minifyEnabled false, so these rules are currently inert. They are kept
# so the file referenced by app/build.gradle exists, and so enabling R8 later is a one-line
# change rather than an archaeology exercise.

# Shizuku's binder shim is reached reflectively by the system.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Room generates implementations that are looked up by name at runtime.
-keep class * extends androidx.room.RoomDatabase { *; }

# Catalog models are deserialized reflectively; keep their fields.
-keepclassmembers class com.legs.appsforaa.model.** { *; }

# Line numbers make crash reports from a release build readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
