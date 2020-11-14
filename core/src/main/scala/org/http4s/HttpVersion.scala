/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s

import cats.{Order, Show}
import cats.data.{Writer => _}
import cats.implicits._
import cats.parse.{Parser => P, Parser1}
import org.http4s.internal.parsing.Rfc5234._
import org.http4s.util._

/** An HTTP version, as seen on the start line of an HTTP request or response.
  *
  * @see [http://tools.ietf.org/html/rfc7230#section-2.6 RFC 7320, Section 2.6
  */
final case class HttpVersion private[HttpVersion] (major: Int, minor: Int)
    extends Renderable
    with Ordered[HttpVersion] {
  override def render(writer: Writer): writer.type = writer << "HTTP/" << major << '.' << minor
  override def compare(that: HttpVersion): Int =
    (this.major, this.minor).compare((that.major, that.minor))
}

object HttpVersion {
  val `HTTP/1.0` = new HttpVersion(1, 0)
  val `HTTP/1.1` = new HttpVersion(1, 1)
  val `HTTP/2.0` = new HttpVersion(2, 0)

  private[this] val right_1_1 = Right(`HTTP/1.1`)
  private[this] val right_1_0 = Right(`HTTP/1.0`)

  def fromString(s: String): ParseResult[HttpVersion] =
    s match {
      case "HTTP/1.1" => right_1_1
      case "HTTP/1.0" => right_1_0
      case _ =>
        parser.parseAll(s).leftMap { _ =>
          ParseFailure("Invalid HTTP version", s"$s was not found to be a valid HTTP version")
        }
    }

  private val parser: Parser1[HttpVersion] = {
    // HTTP-name = %x48.54.54.50 ; HTTP
    val httpName = P.string1("HTTP")

    // HTTP-version = HTTP-name "/" DIGIT "." DIGIT
    val httpVersion = httpName ~ P.char('/') *> digit ~ (P.char('.') *> digit)

    httpVersion.map { case (major, minor) =>
      new HttpVersion(major - '0', minor - '0')
    }
  }

  def fromVersion(major: Int, minor: Int): ParseResult[HttpVersion] =
    if (major < 0) ParseResult.fail("Invalid HTTP version", s"major must be > 0: $major")
    else if (major > 9) ParseResult.fail("Invalid HTTP version", s"major must be <= 9: $major")
    else if (minor < 0) ParseResult.fail("Invalid HTTP version", s"major must be > 0: $minor")
    else if (minor > 9) ParseResult.fail("Invalid HTTP version", s"major must be <= 9: $minor")
    else ParseResult.success(new HttpVersion(major, minor))

  implicit val http4sHttpOrderForVersion: Order[HttpVersion] =
    Order.fromComparable
  implicit val http4sHttpShowForVersion: Show[HttpVersion] =
    Show.fromToString
}
