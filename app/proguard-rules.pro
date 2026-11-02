# Room and kotlinx.serialization publish consumer rules. Keep only Android entry
# points whose names are referenced from the manifest or persisted Work requests.
-keep class org.rankoffline.app.RankOfflineApplication { *; }
-keep class org.rankoffline.app.MainActivity { *; }
-keep class org.rankoffline.app.PackBackgroundWorker { public <init>(...); }

# Preserve generic signatures and annotations used by Room/KSP-generated code.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
