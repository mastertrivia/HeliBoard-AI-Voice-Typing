# Keep native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep classes that are used as a parameter type of methods that are also marked as keep
# to preserve changing those methods' signature.
-keep class helium314.keyboard.latin.dictionary.Dictionary
-keep class helium314.keyboard.latin.NgramContext
-keep class helium314.keyboard.latin.makedict.ProbabilityInfo

# after upgrading to gradle 8, stack traces contain "unknown source"
-keepattributes SourceFile,LineNumberTable
-dontobfuscate

# Compile-time-only annotations referenced by transitive dependencies (e.g. Google Tink)
-dontwarn com.google.errorprone.annotations.**

# Desh Hindi predictor JNI bridge: exported JNI names depend on this exact class/method names.
-keep class com.deshkeyboard.suggestions.nativesuggestions.nativepredictor.NativePredictor { *; }
-keep class helium314.keyboard.latin.DeshHindiPredictor { *; }
