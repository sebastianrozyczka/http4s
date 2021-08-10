/*
 * Copyright 2019 http4s.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.http4s.ember.server.internal

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.syntax.all._
import com.comcast.ip4s.SocketAddress
import fs2.Stream
import fs2.io.tcp._
import fs2.io.tls._
import java.net.InetSocketAddress
import org.http4s._
import org.http4s.ember.core.Util._
import org.http4s.ember.core.{Drain, EmberException, Encoder, Parser, Read}
import org.http4s.headers.{Connection, Date}
import org.http4s.internal.tls.{deduceKeyLength, getCertChain}
import org.http4s.server.{SecureSession, ServerRequestKeys}
import org.typelevel.log4cats.Logger
import org.typelevel.vault.Vault
import scala.concurrent.duration._
import scodec.bits.ByteVector
import org.http4s.headers.Connection

private[server] object ServerHelpers {

  private val serverFailure =
    Response(Status.InternalServerError).putHeaders(org.http4s.headers.`Content-Length`.zero)

  def server[F[_]: ContextShift](
      bindAddress: InetSocketAddress,
      httpApp: HttpApp[F],
      sg: SocketGroup,
      tlsInfoOpt: Option[(TLSContext, TLSParameters)],
      ready: Deferred[F, Either[Throwable, Unit]],
      shutdown: Shutdown[F],
      // Defaults
      errorHandler: Throwable => F[Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit],
      maxConnections: Int,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      idleTimeout: Duration,
      additionalSocketOptions: List[SocketOptionMapping[_]] = List.empty,
      logger: Logger[F]
  )(implicit F: Concurrent[F], T: Timer[F]): Stream[F, Nothing] = {

    val server: Stream[F, Resource[F, Socket[F]]] =
      Stream
        .resource(
          sg.serverResource[F](bindAddress, additionalSocketOptions = additionalSocketOptions))
        .attempt
        .evalTap(e => ready.complete(e.void))
        .rethrow
        .flatMap { case (_, clients) => clients }

    val streams: Stream[F, Stream[F, Nothing]] = server
      .interruptWhen(shutdown.signal.attempt)
      .map { connect =>
        val handler = shutdown.trackConnection >>
          Stream
            .resource(connect.flatMap(upgradeSocket(_, tlsInfoOpt, logger)))
            .flatMap(
              runConnection(
                _,
                logger,
                idleTimeout,
                receiveBufferSize,
                maxHeaderSize,
                requestHeaderReceiveTimeout,
                httpApp,
                errorHandler,
                onWriteFailure
              ))

        handler.handleErrorWith { t =>
          Stream.eval(logger.error(t)("Request handler failed with exception")).drain
        }
      }

    StreamForking.forking(streams, maxConnections)
  }

  // private[internal] def reachedEndError[F[_]: Sync](
  //     socket: Socket[F],
  //     idleTimeout: Duration,
  //     receiveBufferSize: Int): Stream[F, Byte] =
  //   Stream.repeatEval(socket.read(receiveBufferSize, durationToFinite(idleTimeout))).flatMap {
  //     case None =>
  //       Stream.raiseError(new EOFException("Unexpected EOF - socket.read returned None") with NoStackTrace)
  //     case Some(value) => Stream.chunk(value)
  //   }

  private[internal] def upgradeSocket[F[_]: Concurrent: ContextShift](
      socketInit: Socket[F],
      tlsInfoOpt: Option[(TLSContext, TLSParameters)],
      logger: Logger[F]
  ): Resource[F, Socket[F]] =
    tlsInfoOpt.fold(socketInit.pure[Resource[F, *]]) { case (context, params) =>
      context
        .server(socketInit, params, { (s: String) => logger.trace(s) }.some)
        .widen[Socket[F]]
    }

  private[internal] def runApp[F[_]: Concurrent: Timer](
      buffer: Array[Byte],
      read: Read[F],
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      httpApp: HttpApp[F],
      errorHandler: Throwable => F[Response[F]],
      requestVault: Vault): F[(Request[F], Response[F], Drain[F])] = {

    val parse = Parser.Request.parser(maxHeaderSize)(buffer, read)
    val parseWithHeaderTimeout =
      durationToFinite(requestHeaderReceiveTimeout).fold(parse)(duration =>
        parse.timeoutTo(
          duration,
          ApplicativeThrow[F].raiseError(
            new java.util.concurrent.TimeoutException(
              s"Timed Out on EmberServer Header Receive Timeout: $duration"))))

    for {
      tmp <- parseWithHeaderTimeout
      (req, drain) = tmp
      resp <- httpApp
        .run(req.withAttributes(requestVault))
        .handleErrorWith(errorHandler)
        .handleError(_ => serverFailure.covary[F])
    } yield (req, resp, drain)
  }

  private[internal] def send[F[_]: Sync](socket: Socket[F])(
      request: Option[Request[F]],
      resp: Response[F],
      idleTimeout: Duration,
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit]): F[Unit] =
    Encoder
      .respToBytes[F](resp)
      .through(socket.writes(durationToFinite(idleTimeout)))
      .compile
      .drain
      .attempt
      .flatMap {
        case Left(err) =>
          onWriteFailure(request, resp, err)
        case Right(()) => Sync[F].pure(())
      }

  private[internal] def postProcessResponse[F[_]: Timer: Monad](
      req: Request[F],
      resp: Response[F]): F[Response[F]] = {
    val connection = connectionFor(req.httpVersion, req.headers)
    for {
      date <- HttpDate.current[F].map(Date(_))
    } yield resp.withHeaders(Headers(date, connection) ++ resp.headers)
  }

  private[internal] def runConnection[F[_]: Concurrent: Timer](
      socket: Socket[F],
      logger: Logger[F],
      idleTimeout: Duration,
      receiveBufferSize: Int,
      maxHeaderSize: Int,
      requestHeaderReceiveTimeout: Duration,
      httpApp: HttpApp[F],
      errorHandler: Throwable => F[org.http4s.Response[F]],
      onWriteFailure: (Option[Request[F]], Response[F], Throwable) => F[Unit]
  ): Stream[F, Nothing] = {
    type State = (Array[Byte], Boolean)
    val _ = logger
    val read: Read[F] = socket.read(receiveBufferSize, durationToFinite(idleTimeout))
    Stream.eval(mkRequestVault(socket)).flatMap { requestVault =>
      Stream
        .unfoldEval[F, State, (Request[F], Response[F])](Array.emptyByteArray -> false) {
          case (buffer, reuse) =>
            val initRead: F[Array[Byte]] = if (buffer.nonEmpty) {
              // next request has already been (partially) received
              buffer.pure[F]
            } else if (reuse) {
              // the connection is keep-alive, but we don't have any bytes.
              // we want to be on the idle timeout until the next request is received.
              read.flatMap {
                case Some(chunk) => chunk.toArray.pure[F]
                case None => Concurrent[F].raiseError(EmberException.EmptyStream())
              }
            } else {
              // first request begins immediately
              Array.emptyByteArray.pure[F]
            }

            val result = initRead.flatMap { initBuffer =>
              runApp(
                initBuffer,
                read,
                maxHeaderSize,
                requestHeaderReceiveTimeout,
                httpApp,
                errorHandler,
                requestVault)
            }

            result.attempt.flatMap {
              case Right((req, resp, drain)) =>
                // TODO: Should we pay this cost for every HTTP request?
                // Intercept the response for various upgrade paths
                resp.attributes.lookup(org.http4s.server.websocket.websocketKey[F]) match {
                  case Some(ctx) =>
                    drain.flatMap {
                      case Some(buffer) =>
                        WebSocketHelpers
                          .upgrade(
                            socket,
                            req,
                            ctx,
                            buffer,
                            receiveBufferSize,
                            idleTimeout,
                            onWriteFailure,
                            errorHandler,
                            logger)
                          .as(None)
                      case None =>
                        Concurrent[F].pure(None)
                    }
                  case None =>
                    for {
                      nextResp <- postProcessResponse(req, resp)
                      _ <- send(socket)(Some(req), nextResp, idleTimeout, onWriteFailure)
                      nextBuffer <- drain
                    } yield nextBuffer.map(buffer => ((req, nextResp), (buffer, true)))
                }
              case Left(err) =>
                err match {
                  case EmberException.EmptyStream() =>
                    Applicative[F].pure(None)
                  case err =>
                    errorHandler(err)
                      .handleError(_ => serverFailure.covary[F])
                      .flatMap(send(socket)(None, _, idleTimeout, onWriteFailure))
                      .as(None)
                }
            }
        }
        .takeWhile { case (_, resp) =>
          resp.headers.get[Connection].exists(_.hasKeepAlive)
        }
        .drain
    }
  }

  private def mkRequestVault[F[_]: Applicative](socket: Socket[F]) =
    (mkConnectionInfo(socket), mkSecureSession(socket)).mapN(_ ++ _)

  private def mkConnectionInfo[F[_]: Apply](socket: Socket[F]) =
    (socket.localAddress, socket.remoteAddress).mapN {
      case (local: InetSocketAddress, remote: InetSocketAddress) =>
        Vault.empty.insert(
          Request.Keys.ConnectionInfo,
          Request.Connection(
            local = SocketAddress.fromInetSocketAddress(local),
            remote = SocketAddress.fromInetSocketAddress(remote),
            secure = socket.isInstanceOf[TLSSocket[F]]
          )
        )
      case _ =>
        Vault.empty
    }

  private def mkSecureSession[F[_]: Applicative](socket: Socket[F]) =
    socket match {
      case socket: TLSSocket[F] =>
        socket.session
          .map { session =>
            (
              Option(session.getId).map(ByteVector(_).toHex),
              Option(session.getCipherSuite),
              Option(session.getCipherSuite).map(deduceKeyLength),
              Some(getCertChain(session))
            ).mapN(SecureSession.apply)
          }
          .map(Vault.empty.insert(ServerRequestKeys.SecureSession, _))
      case _ =>
        Vault.empty.pure[F]
    }
}
