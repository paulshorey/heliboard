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

# Optional Reactor instrumentation from the Speech SDK's azure-core dependency.
# Neither server-side Micrometer context propagation nor BlockHound is enabled here.
-dontwarn io.micrometer.context.ContextAccessor
-dontwarn reactor.blockhound.integration.BlockHoundIntegration
