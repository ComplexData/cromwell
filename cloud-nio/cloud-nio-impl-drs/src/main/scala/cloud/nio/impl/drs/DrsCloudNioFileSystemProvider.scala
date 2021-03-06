package cloud.nio.impl.drs

import java.net.URI

import cloud.nio.impl.drs.DrsCloudNioFileProvider.DrsReadInterpreter
import cloud.nio.spi.{CloudNioFileProvider, CloudNioFileSystemProvider}
import com.google.auth.oauth2.OAuth2Credentials
import com.typesafe.config.Config
import org.apache.http.impl.client.HttpClientBuilder
import net.ceedubs.ficus.Ficus._

import scala.concurrent.duration.FiniteDuration


class DrsCloudNioFileSystemProvider(rootConfig: Config,
                                    authCredentials: OAuth2Credentials,
                                    httpClientBuilder: HttpClientBuilder,
                                    drsReadInterpreter: DrsReadInterpreter,
                                   ) extends CloudNioFileSystemProvider {

  lazy val marthaUrl: String = rootConfig.getString("martha.url")

  lazy val drsConfig: DrsConfig = DrsConfig(marthaUrl)

  lazy val accessTokenAcceptableTTL: FiniteDuration = rootConfig.as[FiniteDuration]("access-token-acceptable-ttl")

  lazy val drsPathResolver: EngineDrsPathResolver =
    EngineDrsPathResolver(drsConfig, httpClientBuilder, accessTokenAcceptableTTL, authCredentials)

  override def config: Config = rootConfig

  override def fileProvider: CloudNioFileProvider =
    new DrsCloudNioFileProvider(getScheme, drsPathResolver, drsReadInterpreter)

  override def isFatal(exception: Exception): Boolean = false

  override def isTransient(exception: Exception): Boolean = false

  override def getScheme: String = "drs"

  override def getHost(uriAsString: String): String = {
    require(uriAsString.startsWith(s"$getScheme://"), s"Scheme does not match $getScheme")

    /*
     * In some cases for a URI, the host name is null. For example, for DRS urls like 'drs://dg.123/123-123-123',
     * even though 'dg.123' is a valid host, somehow since it does not conform to URI's standards, uri.getHost returns null. In such
     * cases, authority is used instead of host. If there is no authority, use an empty string.
     */
    val uri = new URI(uriAsString)
    val hostOrAuthorityOrEmpty =
      Option(uri.getHost).getOrElse(
        Option(uri.getAuthority).getOrElse("")
      )

    hostOrAuthorityOrEmpty
  }
}
