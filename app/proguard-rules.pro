# The signing module reflects on no types, but BouncyCastle ships providers
# that R8 cannot see being used.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
