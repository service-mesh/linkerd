package io.buoyant.namerd.iface

import com.twitter.conversions.time._
import com.fasterxml.jackson.annotation.JsonIgnore
import com.twitter.finagle._
import com.twitter.finagle.buoyant.TlsClientPrep
import com.twitter.finagle.naming.NameInterpreter
import com.twitter.finagle.param.HighResTimer
import com.twitter.finagle.service._
import com.twitter.logging.Logger
import com.twitter.util.{NonFatal, Throw}
import io.buoyant.namer.{InterpreterConfig, InterpreterInitializer}

/**
 * The namerd interpreter offloads the responsibilities of name resolution to
 * the namerd service via the namerd HTTP streaming API.  Any namers configured
 * in this linkerd are not used.
 */
class NamerdHttpInterpreterInitializer extends InterpreterInitializer {
  val configClass = classOf[NamerdHttpInterpreterConfig]
  override def configId: String = "io.l5d.namerd.http"
}

object NamerdHttpInterpreterInitializer extends NamerdHttpInterpreterInitializer

case class NamerdHttpInterpreterConfig(
  dst: Option[Path],
  namespace: Option[String],
  retry: Option[Retry],
  tls: Option[ClientTlsConfig]
) extends InterpreterConfig {

  @JsonIgnore
  private[this] val log = Logger.get()

  @JsonIgnore
  val defaultRetry = Retry(5, 10.minutes.inSeconds)

  @JsonIgnore
  override val experimentalRequired = true

  /**
   * Construct a namer.
   */
  @JsonIgnore
  def newInterpreter(params: Stack.Params): NameInterpreter = {
    val name = dst match {
      case None => throw new IllegalArgumentException("`dst` is a required field")
      case Some(dst) => Name.Path(dst)
    }
    val label = s"interpreter/${NamerdHttpInterpreterInitializer.configId}"

    val Retry(baseRetry, maxRetry) = retry.getOrElse(defaultRetry)
    val backoffs = Backoff.exponentialJittered(baseRetry.seconds, maxRetry.seconds)

    // replaces the client's retry filter with one that retries unconditionally
    val retryTransformer = new Stack.Transformer {
      def apply[Req, Rsp](stk: Stack[ServiceFactory[Req, Rsp]]) =
        stk.replace(Retries.Role, module[Req, Rsp])

      def module[Req, Rsp]: Stackable[ServiceFactory[Req, Rsp]] =
        new Stack.Module1[param.Stats, ServiceFactory[Req, Rsp]] {
          val role = Retries.Role
          val description = "Retries on any non-fatal error"
          def make(_stats: param.Stats, next: ServiceFactory[Req, Rsp]) = {
            val param.Stats(stats) = _stats
            val retry = new RetryFilter[Req, Rsp](
              RetryPolicy.backoff(backoffs) {
                case (_, Throw(NonFatal(ex))) =>
                  log.error(ex, "namerd request failed")
                  true
              },
              HighResTimer.Default,
              stats,
              RetryBudget.Infinite
            )
            retry.andThen(next)
          }
        }
    }

    val tlsTransformer = new Stack.Transformer {
      override def apply[Req, Rep](stack: Stack[ServiceFactory[Req, Rep]]) = {
        tls match {
          case Some(tlsConfig) =>
            TlsClientPrep.static[Req, Rep](tlsConfig.commonName, tlsConfig.caCert) +: stack
          case None => stack
        }
      }
    }

    val client = Http.client
      .withParams(Http.client.params ++ params)
      .withSessionQualifier.noFailFast
      .withSessionQualifier.noFailureAccrual
      .withStreaming(true)
      .transformed(retryTransformer)
      .transformed(tlsTransformer)

    new StreamingNamerClient(client.newService(name, label), namespace.getOrElse("default"))
  }
}
