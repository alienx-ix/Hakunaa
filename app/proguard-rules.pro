# Keep FFmpegKit and PDFBox from being stripped in release builds.
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.arthenica.**
-dontwarn com.tom_roush.**
