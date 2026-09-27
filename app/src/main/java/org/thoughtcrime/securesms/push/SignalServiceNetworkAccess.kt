package org.thoughtcrime.securesms.push

import android.content.Context
import android.net.ConnectivityManager
import android.net.ProxyInfo
import android.net.Uri
import androidx.core.content.ContextCompat
import okhttp3.Dns
import okhttp3.Interceptor
import org.signal.core.util.Base64
import org.signal.core.util.logging.Log
import org.signal.network.config.HttpProxy
import org.signal.network.config.SignalCdnUrl
import org.signal.network.config.SignalCdsiUrl
import org.signal.network.config.SignalServiceConfiguration
import org.signal.network.config.SignalServiceUrl
import org.signal.network.config.SignalStorageUrl
import org.signal.network.config.SignalSvr2Url
import org.signal.network.config.TrustStore
import org.thoughtcrime.securesms.BuildConfig
import org.thoughtcrime.securesms.keyvalue.SignalStore
import org.thoughtcrime.securesms.net.CustomDns
import org.thoughtcrime.securesms.net.DeprecatedClientPreventionInterceptor
import org.thoughtcrime.securesms.net.DeviceTransferBlockingInterceptor
import org.thoughtcrime.securesms.net.RemoteDeprecationDetectorInterceptor
import org.thoughtcrime.securesms.net.SequentialDns
import org.thoughtcrime.securesms.net.StandardUserAgentInterceptor
import org.thoughtcrime.securesms.net.StaticDns
import org.thoughtcrime.securesms.net.StorageServiceSizeLoggingInterceptor
import java.io.IOException
import java.util.Optional

/**
 * Provides a [SignalServiceConfiguration] to be used with our service layer.
 * If you're looking for a place to start, look at [getConfiguration].
 *
 * SWARM: there is one configuration, the SWARM hosts from `BuildConfig`. Upstream also builds
 * "censorship circumvention" configurations that domain-front through Google and Fastly to *Signal's*
 * hosts (`chat-signal.global.ssl.fastly.net`, `storage.signal.org.global.prod.fastly.net`, ...) and turns
 * them on by default for nine country codes. SWARM runs no fronts, and a SWARM build must never talk to
 * Signal, so those configurations are gone and [getConfiguration] always returns [uncensoredConfiguration].
 */
class SignalServiceNetworkAccess(context: Context) {
  companion object {
    private val TAG = Log.tag(SignalServiceNetworkAccess::class.java)

    /**
     * System DNS first, then 1.1.1.1, then the static addresses built into the app. SWARM ships no static
     * addresses (app/static-ips.properties is empty), so the last step never answers.
     */
    @JvmField
    val DNS: Dns = SequentialDns(
      Dns.SYSTEM,
      CustomDns("1.1.1.1"),
      StaticDns(
        mapOf(
          BuildConfig.SIGNAL_URL.stripProtocol() to BuildConfig.SIGNAL_SERVICE_IPS.toSet(),
          BuildConfig.STORAGE_URL.stripProtocol() to BuildConfig.SIGNAL_STORAGE_IPS.toSet(),
          BuildConfig.SIGNAL_CDN_URL.stripProtocol() to BuildConfig.SIGNAL_CDN_IPS.toSet(),
          BuildConfig.SIGNAL_CDN2_URL.stripProtocol() to BuildConfig.SIGNAL_CDN2_IPS.toSet(),
          BuildConfig.SIGNAL_CDN3_URL.stripProtocol() to BuildConfig.SIGNAL_CDN3_IPS.toSet(),
          BuildConfig.SIGNAL_SFU_URL.stripProtocol() to BuildConfig.SIGNAL_SFU_IPS.toSet(),
          BuildConfig.CONTENT_PROXY_HOST.stripProtocol() to BuildConfig.SIGNAL_CONTENT_PROXY_IPS.toSet(),
          BuildConfig.SIGNAL_CDSI_URL.stripProtocol() to BuildConfig.SIGNAL_CDSI_IPS.toSet(),
          BuildConfig.SIGNAL_SVR2_URL.stripProtocol() to BuildConfig.SIGNAL_SVR2_IPS.toSet()
        )
      )
    )

    private fun String.stripProtocol(): String {
      return this.removePrefix("https://")
    }

    @Suppress("DEPRECATION")
    private fun getSystemHttpProxy(context: Context): HttpProxy? {
      val connectivityManager = ContextCompat.getSystemService(context, ConnectivityManager::class.java) ?: return null

      val proxyInfo = connectivityManager
        .activeNetwork
        ?.let { connectivityManager.getLinkProperties(it)?.httpProxy }

      return proxyInfo.toApplicableSystemHttpProxy()
    }

    fun ProxyInfo?.toApplicableSystemHttpProxy(): HttpProxy? {
      return this
        ?.takeIf { !it.exclusionList.contains(BuildConfig.SIGNAL_URL.stripProtocol()) }
        // NB: Edit carefully, dear reader, as the line below is written from hard won experience.
        // It turns out, that despite being documented *nowhere*, if a PAC file is set
        //   as the system proxy, proxyInfo.host will return "localhost" and proxyInfo.port
        //   will return -1.
        // I learnt this by reading the AOSP source code for ProxyInfo:
        //   https://android.googlesource.com/platform/frameworks/base/+/4696ee4/core/java/android/net/ProxyInfo.java#107
        // So, if we do not explicitly check that a PAC file is not set, the proxy
        //   we pass to libsignal may be syntactically invalid, and the user may be
        //   rendered unable to connect.
        ?.takeIf { it.pacFileUrl == Uri.EMPTY }
        ?.let { proxy -> HttpProxy(proxy.host, proxy.port) }
    }
  }

  private val serviceTrustStore: TrustStore = SignalServiceTrustStore(context)

  private val interceptors: List<Interceptor> = listOf(
    StandardUserAgentInterceptor(),
    StorageServiceSizeLoggingInterceptor(),
    RemoteDeprecationDetectorInterceptor(this::getConfiguration),
    DeprecatedClientPreventionInterceptor(),
    DeviceTransferBlockingInterceptor.getInstance()
  )

  private val zkGroupServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.ZKGROUP_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  private val genericServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.GENERIC_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  private val backupServerPublicParams: ByteArray = try {
    Base64.decode(BuildConfig.BACKUP_SERVER_PUBLIC_PARAMS)
  } catch (e: IOException) {
    throw AssertionError(e)
  }

  val uncensoredConfiguration: SignalServiceConfiguration = SignalServiceConfiguration(
    signalServiceUrls = arrayOf(SignalServiceUrl(BuildConfig.SIGNAL_URL, serviceTrustStore)),
    signalCdnUrlMap = mapOf(
      0 to arrayOf(SignalCdnUrl(BuildConfig.SIGNAL_CDN_URL, serviceTrustStore)),
      2 to arrayOf(SignalCdnUrl(BuildConfig.SIGNAL_CDN2_URL, serviceTrustStore)),
      3 to arrayOf(SignalCdnUrl(BuildConfig.SIGNAL_CDN3_URL, serviceTrustStore))
    ),
    signalStorageUrls = arrayOf(SignalStorageUrl(BuildConfig.STORAGE_URL, serviceTrustStore)),
    signalCdsiUrls = arrayOf(SignalCdsiUrl(BuildConfig.SIGNAL_CDSI_URL, serviceTrustStore)),
    signalSvr2Urls = arrayOf(SignalSvr2Url(BuildConfig.SIGNAL_SVR2_URL, serviceTrustStore)),
    networkInterceptors = interceptors,
    dns = Optional.of(DNS),
    signalProxy = if (SignalStore.proxy.isProxyEnabled) Optional.ofNullable(SignalStore.proxy.proxy) else Optional.empty(),
    systemHttpProxy = Optional.ofNullable(getSystemHttpProxy(context)),
    zkGroupServerPublicParams = zkGroupServerPublicParams,
    genericServerPublicParams = genericServerPublicParams,
    backupServerPublicParams = backupServerPublicParams,
    censored = false
  )

  fun getConfiguration(): SignalServiceConfiguration {
    return uncensoredConfiguration
  }

  @Suppress("UNUSED_PARAMETER")
  fun getConfiguration(e164: String?): SignalServiceConfiguration {
    return uncensoredConfiguration
  }

  fun isCensored(): Boolean {
    return false
  }

  @Suppress("UNUSED_PARAMETER")
  fun isCensored(number: String?): Boolean {
    return false
  }

  @Suppress("UNUSED_PARAMETER")
  fun isCountryCodeCensoredByDefault(countryCode: Int): Boolean {
    return false
  }
}
