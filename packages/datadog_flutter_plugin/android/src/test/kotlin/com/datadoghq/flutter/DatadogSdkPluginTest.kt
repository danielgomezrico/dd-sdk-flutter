/*
 * Unless explicitly stated otherwise all files in this repository are licensed under the Apache License Version 2.0.
 * This product includes software developed at Datadog (https://www.datadoghq.com/).
 * Copyright 2016-Present Datadog, Inc.
 */
package com.datadoghq.flutter

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import assertk.assertThat
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import com.datadog.android.Datadog
import com.datadog.android.core.model.UserInfo
import com.datadog.android.privacy.TrackingConsent
import com.datadog.android.rum.GlobalRum
import fr.xgouchet.elmyr.Forge
import fr.xgouchet.elmyr.annotation.Forgery
import fr.xgouchet.elmyr.annotation.StringForgery
import fr.xgouchet.elmyr.junit5.ForgeExtension
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.opentracing.util.GlobalTracer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExtendWith(ForgeExtension::class)
class DatadogSdkPluginTest {
    lateinit var plugin: DatadogSdkPlugin

    @BeforeEach
    fun beforeEach() {
        plugin = DatadogSdkPlugin()
        val mockFlutterPluginBinding = mock<FlutterPlugin.FlutterPluginBinding>()
        whenever(mockFlutterPluginBinding.binaryMessenger).thenReturn(mock())
        // Mock everything to get Datadog to initialize properly
        val mockPackageInfo = mock<PackageInfo> {
            mock.versionName = "version"
            mock.versionCode = 1124
        }
        val mockPackageManager = mock<PackageManager> {
            on { getPackageInfo(any<String>(), any()) } doReturn mockPackageInfo
        }
        val mockPreferences = mock<SharedPreferences>()
        val mockContext = mock<Context>() {
            on { applicationInfo } doReturn mock()
            on { applicationContext } doReturn this.mock
            on { packageName } doReturn "fakePackage"
            on { packageManager } doReturn mockPackageManager
            on { getSharedPreferences(any(), any()) } doReturn mockPreferences
        }
        whenever(mockFlutterPluginBinding.applicationContext).thenReturn(mockContext)
        plugin.onAttachedToEngine(mockFlutterPluginBinding)
    }

    @AfterEach
    fun afterEach() {
        Datadog.invokeMethod("flushAndShutdownExecutors")
    }

    @Test
    fun `M not initialize features W no nested configuration`(
        @StringForgery clientToken: String,
        @StringForgery environment: String
    ) {
        // GIVEN
        val configuration = DatadogFlutterConfiguration(
            clientToken = clientToken,
            env = environment,
            nativeCrashReportEnabled = false,
            trackingConsent = TrackingConsent.GRANTED
        )

        // WHEN
        plugin.initialize(configuration)

        // THEN
        assertThat(Datadog.isInitialized()).isTrue()
        assertThat(plugin.tracesPlugin).isNull()
        assertThat(plugin.rumPlugin).isNull()

        // Because we have no way to reset these, we can't test
        // that they're registered properly.
        //assertThat(GlobalRum.isRegistered()).isFalse()
        //assertThat(GlobalTracer.isRegistered()).isFalse()
    }

    @Test
    fun `M initialize traces W DatadogFlutterConfiguation { tracingConfiguration }`(
        @StringForgery clientToken: String,
        @StringForgery environment: String
    ) {
        // GIVEN
        val configuration = DatadogFlutterConfiguration(
            clientToken = clientToken,
            env = environment,
            nativeCrashReportEnabled = false,
            trackingConsent = TrackingConsent.GRANTED,
            tracingConfiguration = DatadogFlutterConfiguration.TracingConfiguration(
                sendNetworkInfo = true,
                bundleWithRum = true
            )
        )

        // WHEN
        plugin.initialize(configuration)

        // THEN
        assertThat(plugin.tracesPlugin).isNotNull()
        // NOTE: We have no way of knowing if this was set in a previous test
        assertThat(GlobalTracer.isRegistered()).isTrue()
    }

    @Test
    fun `M initialize RUM W DatadogFlutterConfiguration { rumConfiguration }`(
        @StringForgery clientToken: String,
        @StringForgery environment: String,
        @StringForgery applicationId: String
    ) {
        // GIVEN
        val configuration = DatadogFlutterConfiguration(
            clientToken = clientToken,
            env = environment,
            nativeCrashReportEnabled = false,
            trackingConsent = TrackingConsent.GRANTED,
            rumConfiguration = DatadogFlutterConfiguration.RumConfiguration(
                applicationId = applicationId,
                sampleRate = 82.3f
            )
        )

        // WHEN
        plugin.initialize(configuration)

        // THEN
        assertThat(plugin.rumPlugin).isNotNull()
        // NOTE: We have no way of knowing if this was set in a previous test
        assertThat(GlobalRum.isRegistered()).isTrue()
    }

    @Test
    fun `M initialize Datadog W called through MethodChannel`(
        @StringForgery clientToken: String,
        @StringForgery environment: String,
        @StringForgery applicationId: String
    ) {
        // GIVEN
        val methodCall = MethodCall(
            "initialize",
            mapOf(
                "configuration" to mapOf(
                    "clientToken" to clientToken,
                    "env" to environment,
                    "trackingConsent" to "TrackingConsent.granted",
                    "nativeCrashReportEnabled" to true
                )
            )
        )
        val mockResult = mock<MethodChannel.Result>()

        // WHEN
        plugin.onMethodCall(methodCall, mockResult)

        // THEN
        assertThat(Datadog.isInitialized())
        assertThat(plugin.logsPlugin).isNotNull()
        verify(mockResult).success(null)
    }

    @Test
    fun `M set sdk verbosity W called through MethodChannel`() {
        // GIVEN
        var methodCall = MethodCall(
            "setSdkVerbosity",
            mapOf( "value" to "Verbosity.info" )
        )
        val mockResult = mock<MethodChannel.Result>()

        // WHEN
        plugin.onMethodCall(methodCall, mockResult)

        // THEN
        val setVerbosity: Int = Datadog.getFieldValue("libraryVerbosity")
        assertThat(setVerbosity).equals(Log.INFO)
        verify(mockResult).success(null)
    }

    @Test
    fun `M set tracking consent W called through MethodChannel`(
        forge: Forge
    ) {
        // GIVEN
        val configuration = DatadogFlutterConfiguration(
            clientToken = forge.aString(),
            env = forge.anAlphabeticalString(),
            nativeCrashReportEnabled = false,
            trackingConsent = TrackingConsent.GRANTED
        )
        plugin.initialize(configuration)

        var methodCall = MethodCall(
            "setTrackingConsent",
            mapOf( "value" to "TrackingConsent.notGranted" )
        )
        val mockResult = mock<MethodChannel.Result>()

        // WHEN
        plugin.onMethodCall(methodCall, mockResult)

        // THEN
        var coreFeature = Class.forName("com.datadog.android.core.internal.CoreFeature")
            .kotlin.objectInstance
        var trackingConsent: TrackingConsent? = coreFeature
            ?.getFieldValue<Any, Any>("trackingConsentProvider")
            ?.getFieldValue("consent")
        assertThat(trackingConsent).isEqualTo(TrackingConsent.NOT_GRANTED)
        verify(mockResult).success(null)
    }

    @Test
    fun `M set user info W called through MethodChannel`(
        @StringForgery id: String,
        @StringForgery name: String,
        @StringForgery email: String,
        forge: Forge
    ) {
        // GIVEN
        val configuration = DatadogFlutterConfiguration(
            clientToken = forge.aString(),
            env = forge.anAlphabeticalString(),
            nativeCrashReportEnabled = false,
            trackingConsent = TrackingConsent.GRANTED
        )
        plugin.initialize(configuration)

        var methodCall = MethodCall(
            "setUserInfo",
            mapOf(
                "id" to id,
                "name" to name,
                "email" to email,
                "extraInfo" to mapOf<String, Any?>()
            )
        )
        val mockResult = mock<MethodChannel.Result>()

        // WHEN
        plugin.onMethodCall(methodCall, mockResult)

        // THEN
        var coreFeature = Class.forName("com.datadog.android.core.internal.CoreFeature")
            .kotlin.objectInstance
        var userInfo: UserInfo? = coreFeature
            ?.getFieldValue<Any, Any>("userInfoProvider")
            ?.getFieldValue("internalUserInfo")
        assertThat(userInfo?.id).isEqualTo(id)
        assertThat(userInfo?.name).isEqualTo(name)
        assertThat(userInfo?.email).isEqualTo(email)
        assertThat(userInfo?.additionalProperties).isNotNull().isEmpty()
        verify(mockResult).success(null)
    }

    @Test
    fun `M set user info W called through MethodChannel(null values, exhaustive attributes)`(
        forge: Forge
    ) {
        // GIVEN
        val configuration = DatadogFlutterConfiguration(
            clientToken = forge.aString(),
            env = forge.anAlphabeticalString(),
            nativeCrashReportEnabled = false,
            trackingConsent = TrackingConsent.GRANTED
        )
        plugin.initialize(configuration)

        val id = forge.aNullable { forge.aString() }
        val name = forge.aNullable { forge.aString() }
        val email = forge.aNullable { forge.aString() }
        val extraInfo = forge.exhaustiveAttributes()
        var methodCall = MethodCall(
            "setUserInfo",
            mapOf(
                "id" to id,
                "name" to name,
                "email" to email,
                "extraInfo" to extraInfo
            )
        )
        val mockResult = mock<MethodChannel.Result>()

        // WHEN
        plugin.onMethodCall(methodCall, mockResult)

        // THEN
        var coreFeature = Class.forName("com.datadog.android.core.internal.CoreFeature")
            .kotlin.objectInstance
        var userInfo: UserInfo? = coreFeature
            ?.getFieldValue<Any, Any>("userInfoProvider")
            ?.getFieldValue("internalUserInfo")
        assertThat(userInfo?.id).isEqualTo(id)
        assertThat(userInfo?.name).isEqualTo(name)
        assertThat(userInfo?.email).isEqualTo(email)
        assertThat(userInfo?.additionalProperties).isEqualTo(extraInfo)
        verify(mockResult).success(null)
    }
}