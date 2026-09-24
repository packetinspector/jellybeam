# JNA: the uniffi-generated bindings (uniffi/jellybeam_core/jellybeam_core.kt)
# load libjellybeam_core.so through JNA's reflective Library/Structure/Callback
# machinery. R8 must not strip, rename, or reorder any of it -- Structure
# subclasses' declared field order is the native ABI.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.Library { *; }
-keep class * extends com.sun.jna.Structure { *; }
-keep class * implements com.sun.jna.Callback { *; }
-dontwarn java.awt.*

# The generated bindings themselves are resolved reflectively by JNA
# (interface method names must match the exported C symbols' lookup path),
# so keep the whole generated package verbatim. It's one file; the size
# cost is negligible next to the .so it wraps.
-keep class uniffi.jellybeam_core.** { *; }
