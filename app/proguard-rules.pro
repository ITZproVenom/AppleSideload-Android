# The signing module reflects on no types, but BouncyCastle ships providers
# that R8 cannot see being used.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# The Remote Pairing tunnel drives BouncyCastle's TLS engine directly; keep it
# whole so R8 cannot strip suites or extensions it only reaches through tables.
-keep class org.bouncycastle.tls.** { *; }
-dontwarn org.bouncycastle.**
# Error names appear in the log and on screen ("RemotePairingException: ...");
# keep them readable instead of R8's one-letter names.
-keepnames class * extends java.lang.Throwable
