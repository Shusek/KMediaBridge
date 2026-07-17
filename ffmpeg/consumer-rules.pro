# The bridge exports name-based JNI entry points, so both the native holder
# and its external method names must survive R8.
-keepclasseswithmembernames,includedescriptorclasses class io.github.shusek.kmediabridge.ffmpeg.AndroidFfmpegNative {
    native <methods>;
}

# Keep the method name used by the narrow native callback lookup.
-keepclassmembers class io.github.shusek.kmediabridge.ffmpeg.AndroidNativeByteConsumer {
    boolean accept(byte[]);
}
