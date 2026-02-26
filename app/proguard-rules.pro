# Add project specific ProGuard rules here.
# You can use the generated configuration.txt contents to check for missing rules.

# Hilt
-keep class com.lomo.app.LomoApplication_HiltComponents { *; }
-keep class com.lomo.app.di.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase$Builder
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class * { *; }

# Data Classes (Serialization often needs them, though we rely on Room/Gson)
-keepclassmembers class com.lomo.domain.model.** { <fields>; }
-keepclassmembers class com.lomo.data.local.entity.** { <fields>; }

# Generic Compose Rules (Usually handled by R8 automatically but safe to add)
# Generic Compose Rules (Handled by R8 automatically)


# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Fix for R8 missing kotlin.time classes referenced by kotlinx-serialization
-dontwarn kotlin.time.**
-keep class kotlin.time.** { *; }

# Ktor Netty server optional JDK/desktop deps. Not used on Android but referenced.
# Generated suggestions from R8 missing_rules.txt and additional Reactor BlockHound service.
-dontwarn io.netty.internal.tcnative.AsyncSSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.AsyncTask
-dontwarn io.netty.internal.tcnative.Buffer
-dontwarn io.netty.internal.tcnative.CertificateCallback
-dontwarn io.netty.internal.tcnative.CertificateCompressionAlgo
-dontwarn io.netty.internal.tcnative.CertificateVerifier
-dontwarn io.netty.internal.tcnative.Library
-dontwarn io.netty.internal.tcnative.SSL
-dontwarn io.netty.internal.tcnative.SSLContext
-dontwarn io.netty.internal.tcnative.SSLPrivateKeyMethod
-dontwarn io.netty.internal.tcnative.SSLSessionCache
-dontwarn io.netty.internal.tcnative.SessionTicketKey
-dontwarn io.netty.internal.tcnative.SniHostNameMatcher
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.apache.logging.log4j.Level
-dontwarn org.apache.logging.log4j.LogManager
-dontwarn org.apache.logging.log4j.Logger
-dontwarn org.apache.logging.log4j.message.MessageFactory
-dontwarn org.apache.logging.log4j.spi.ExtendedLogger
-dontwarn org.apache.logging.log4j.spi.ExtendedLoggerWrapper
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ClientProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$Provider
-dontwarn org.eclipse.jetty.npn.NextProtoNego$ServerProvider
-dontwarn org.eclipse.jetty.npn.NextProtoNego
-dontwarn reactor.blockhound.integration.**

# JGit optional desktop/JVM integrations not available on Android runtime.
-dontwarn java.lang.ProcessHandle
-dontwarn javax.management.**
-dontwarn org.ietf.jgss.**
