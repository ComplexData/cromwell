package drs.localizer

import java.nio.file.{Files, Path}

import cats.effect.{ExitCode, IO, IOApp}
import cloud.nio.impl.drs.DrsConfig
import org.apache.http.impl.client.HttpClientBuilder
import org.slf4j.LoggerFactory

import scala.sys.process._

class DrsLocalizerMain extends IOApp {

  val logger = LoggerFactory.getLogger("DrsLocalizerLogger")
  val httpClientBuilder = HttpClientBuilder.create()
  val requestTemplate = """{"url": "${drsPath}"}"""

  val GcsScheme = "gs://"
  val RequesterPaysErrorMsg = "Bucket is requester pays bucket but no user project provided."
  val ExtractGcsUrlErrorMsg = "No resolved url starting with 'gs://' found from Martha response!"

  val CloudPlatformAuthScope = "https://www.googleapis.com/auth/cloud-platform"


  /* This assumes the args are as follows:
      0: DRS input
      1: download location
      2: Optional parameter- Requester Pays Billing project ID
     Martha URL is passed as an environment variable
   */
  override def run(args: List[String]): IO[ExitCode] = {
    val argsLength = args.length

    argsLength match {
      case 2 => resolveAndDownload(args.head, args(1), None)
      case 3 => resolveAndDownload(args.head, args(1), Option(args(2)))
      case _ => {
        val argsList = if (args.nonEmpty) args.mkString(",") else "None"
        logger.error(s"Received $argsLength arguments. DRS input and download location path is required. Requester Pays billing project ID is optional. " +
          s"Arguments received: $argsList")
        IO(ExitCode.Error)
      }
    }
  }


  def getDrsPathResolver: IO[LocalizerDrsPathResolver] = {
    IO.pure {
      val marthaUrl = sys.env("MARTHA_URL")
      val drsConfig = DrsConfig(marthaUrl, requestTemplate)
      new LocalizerDrsPathResolver(drsConfig, httpClientBuilder)
    }
  }


  def resolveAndDownload(drsUrl: String, downloadLoc: String, requesterPaysId: Option[String]): IO[ExitCode] = {
    for {
      localizerDrsPathResolver <- getDrsPathResolver
      marthaResponse <- localizerDrsPathResolver.resolveDrsThroughMartha(drsUrl)
      // Currently Martha only supports resolving DRS paths to GCS paths
      gcsUrl <- IO.fromEither(marthaResponse.gsUri.toRight(new RuntimeException(ExtractGcsUrlErrorMsg)))
      exitState <- downloadFileFromGcs(gcsUrl, marthaResponse.googleServiceAccount.map(_.data.toString), downloadLoc, requesterPaysId)
    } yield exitState
  }


  /*
     Bash to download the GCS file using `gsutil`
   */
  def downloadScript(gcsUrl: String,
                     downloadLoc: String,
                     saJsonPathOption: Option[Path],
                     requesterPaysProjectIdOption: Option[String]): String = {

    def gcsCopyCommand(flag: String = ""): String = s"gsutil $flag cp $gcsUrl $downloadLoc"

    def setServiceAccount(saJsonPathOption: Option[Path]): String = {
      saJsonPathOption match {
        case Some(saJsonPath) =>
          s"""# Set gsutil to use the service account returned from Martha
             |gcloud auth activate-service-account --key-file=$saJsonPath > gcloud_output.txt 2>&1
             |RC_GCLOUD=$$?
             |if [ "$$RC_GCLOUD" != "0" ]; then
             |  echo "Failed to activate service account returned from Martha. File won't be downloaded. Error: $$(cat gcloud_output.txt)" >&2
             |  exit "$$RC_GCLOUD"
             |else
             |  echo "Successfully activated service account; Will continue with download. $$(cat gcloud_output.txt)"
             |fi
             |""".stripMargin
        case None => ""
      }
    }

    def recoverWithRequesterPays(): String = {
      requesterPaysProjectIdOption match {
        case Some(userProject) =>
          s"""# Check if error is requester pays. If yes, retry gsutil copy using project flag
             |if grep -q '$RequesterPaysErrorMsg' gsutil_output.txt; then
             |  echo "Received 'Bucket is requester pays' error. Attempting again using Requester Pays billing project"
             |  ${gcsCopyCommand(s"-u $userProject")}
             |else
             |  echo "Failed to download the file. Error: $$(cat gsutil_output.txt)" >&2
             |  exit "$$RC_GSUTIL"
             |fi
             |""".stripMargin
        case None =>
          s"""  echo "Failed to download the file. Error: $$(cat gsutil_output.txt)" >&2
             |  exit "$$RC_GSUTIL"
             |""".stripMargin
      }
    }

    // bash to download the GCS file using gsutil
    val downloadBashScript = s"""set -euo pipefail
       |set +e
       |
       |${setServiceAccount(saJsonPathOption)}
       |
       |# Run gsutil copy without using project flag
       |${gcsCopyCommand()} > gsutil_output.txt 2>&1
       |RC_GSUTIL=$$?
       |if [ "$$RC_GSUTIL" != "0" ]; then
       |  ${recoverWithRequesterPays()}
       |else
       |  echo "Download complete!"
       |  exit 0
       |fi
       |""".stripMargin

    // log the script for easier debugging
    logger.info(s"Bash script to download file: \n$downloadBashScript")
    downloadBashScript
  }


  def downloadFileFromGcs(gcsUrl: String,
                          serviceAccountJsonOption: Option[String],
                          downloadLoc: String,
                          requesterPaysProjectIdOption: Option[String]) : IO[ExitCode] = {

    logger.info(s"Requester Pays project ID is $requesterPaysProjectIdOption")
    logger.info(s"Attempting to download $gcsUrl to $downloadLoc")

    val returnCode = serviceAccountJsonOption match {
      case Some(sa) =>
        // if Martha returned a SA, use that SA for gsutil instead of default credentials
        val tempCredentialDir: Path = Files.createTempDirectory("gcloudTemp_").toAbsolutePath
        val saJsonPath: Path = tempCredentialDir.resolve("sa.json")
        Files.write(saJsonPath, sa.getBytes("UTF-8")) // set to UTF-8
        val extraEnv = Map("CLOUDSDK_CONFIG" -> tempCredentialDir.toString)

        val copyCommand = Seq("bash", "-c", downloadScript(gcsUrl, downloadLoc, Option(saJsonPath), requesterPaysProjectIdOption))
        // run the multiple bash script to download file and log stream sent to stdout and stderr using ProcessLogger
        Process(copyCommand, None, extraEnv.toSeq: _*) ! ProcessLogger(logger.info, logger.error)
      case None =>
        /*
          No SA returned from Martha. gsutil will use the application default credentials.
          Run the multiple bash script to download file and log stream sent to stdout and stderr using ProcessLogger
         */
        Process(Seq("bash", "-c", downloadScript(gcsUrl, downloadLoc, None, requesterPaysProjectIdOption))) ! ProcessLogger(logger.info, logger.error)
    }

    IO(ExitCode(returnCode))
  }
}
